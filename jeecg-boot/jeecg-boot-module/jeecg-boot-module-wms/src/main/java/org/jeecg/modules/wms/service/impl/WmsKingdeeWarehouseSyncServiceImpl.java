package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeWarehouseSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Description: 仓库档案 ERP 同步（BD_STOCK → wms_warehouse）与本地档案初始化。
 *
 * <p>两条硬约束（spec 2026-10-26 §5.1）：
 * <ul>
 *   <li>白名单为空 → 不查询，避免把测试账套里约 190 个仓整表灌入；</li>
 *   <li>查询必须带组织过滤（{@code FUseOrgId.FNumber}）——同一编码在不同组织下是不同档案，
 *       不带过滤会互相覆盖。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Service
public class WmsKingdeeWarehouseSyncServiceImpl implements IWmsKingdeeWarehouseSyncService {

    private static final String FORM_ID = "BD_STOCK";
    private static final String FIELD_KEYS = "FNumber,FName";
    /** 白名单之外的返回行上限（白名单条数 + 余量，防止金蝶忽略 in 条件时拉全表）。 */
    private static final int TOP_MARGIN = 50;

    private static final String SYNC_OBJECT_TYPE = "WAREHOUSE";
    private static final String SYNC_TARGET = "WMS";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsWarehouseService warehouseService;
    private final WmsWarehouseMapper warehouseMapper;
    private final IWmsSyncLogService syncLogService;

    public WmsKingdeeWarehouseSyncServiceImpl(WmsKingdeeFormClient formClient,
                                              WmsKingdeeProperties properties,
                                              IWmsWarehouseService warehouseService,
                                              WmsWarehouseMapper warehouseMapper,
                                              IWmsSyncLogService syncLogService) {
        this.formClient = formClient;
        this.properties = properties;
        this.warehouseService = warehouseService;
        this.warehouseMapper = warehouseMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    public WmsKingdeeStockSyncResultVO syncStock() {
        WmsKingdeeProperties.MasterData masterData = properties.getMasterData();
        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        List<String> whitelist = masterData.getStockWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            vo.setMessage("仓库同步白名单未配置，跳过（spec 反目标：不得整表灌入）");
            return vo;
        }

        String filter = buildFilter(masterData.getOrgId(), whitelist);
        long start = System.currentTimeMillis();
        WmsKingdeeResult result = formClient.executeBillQuery(
                FORM_ID, FIELD_KEYS, filter, whitelist.size() + TOP_MARGIN);
        if (!result.isSuccess()) {
            logSyncFailed(filter, result.getMessage(), System.currentTimeMillis() - start);
            throw new RuntimeException("金蝶仓库查询失败：" + result.getMessage());
        }

        List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
        vo.setFetched(rows.size());
        Map<String, String> codeMap = masterData.getStockCodeMap();
        for (List<Object> row : rows) {
            if (row.size() < 2) {
                continue;
            }
            String erpCode = asText(row.get(0));
            String erpName = asText(row.get(1));
            if (erpCode.isEmpty()) {
                continue;
            }
            String mappedCode = codeMap == null ? null : codeMap.get(erpCode);
            if (mappedCode != null && !mappedCode.isEmpty()) {
                if (renameMapped(mappedCode, erpCode, erpName)) {
                    vo.setRenamed(vo.getRenamed() + 1);
                }
            } else if (createOrUpdateByErpCode(erpCode, erpName)) {
                vo.setCreated(vo.getCreated() + 1);
            }
        }
        vo.setMessage(String.format("拉取 %d 行，改名 %d，新建 %d",
                vo.getFetched(), vo.getRenamed(), vo.getCreated()));
        logSyncSuccess(filter, vo.getMessage(), System.currentTimeMillis() - start);
        return vo;
    }

    @Override
    public void initLocalArchives() {
        renameIfPresent("LB", "半成品仓");
        renameIfPresent("MJ", "产成品仓");
        createIfAbsent("CJ", "初级产成品仓");
        createIfAbsent("TC", "涂层产成品仓");
    }

    /** 组织 + 白名单过滤。组织过滤是硬约束：同码不同组织是两份档案。 */
    static String buildFilter(String orgId, List<String> whitelist) {
        String codes = whitelist.stream()
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(code -> "'" + code.trim().replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        StringBuilder sb = new StringBuilder();
        if (orgId != null && !orgId.trim().isEmpty()) {
            sb.append("FUseOrgId.FNumber='").append(orgId.trim().replace("'", "''")).append("'");
        }
        if (!codes.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append("FNumber in (").append(codes).append(")");
        }
        return sb.toString();
    }

    /** 命中映射：只改名称与 ERP 列，仓库编码保持不变。 */
    private boolean renameMapped(String warehouseCode, String erpCode, String erpName) {
        WmsWarehouse existing = findByWarehouseCode(warehouseCode);
        if (existing == null) {
            log.warn("仓库映射 {} -> {} 指向不存在的 WMS 仓库，跳过", erpCode, warehouseCode);
            return false;
        }
        existing.setWarehouseName(erpName);
        existing.setErpStockCode(erpCode);
        existing.setErpStockName(erpName);
        warehouseService.editWarehouse(existing);
        return true;
    }

    /**
     * 未命中映射：按 ERP 编码 upsert 新建（返回 true 表示新建）。
     *
     * <p>先按 erp_stock_code 找，再按 warehouse_code 找——重复同步幂等。
     */
    private boolean createOrUpdateByErpCode(String erpCode, String erpName) {
        WmsWarehouse existing = findByErpStockCode(erpCode);
        if (existing == null) {
            existing = findByWarehouseCode(erpCode);
        }
        if (existing != null) {
            existing.setWarehouseName(erpName);
            existing.setErpStockCode(erpCode);
            existing.setErpStockName(erpName);
            warehouseService.editWarehouse(existing);
            return false;
        }
        WmsWarehouse created = new WmsWarehouse();
        created.setWarehouseCode(erpCode);
        created.setWarehouseName(erpName);
        created.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_NORMAL);
        created.setStatus("1");
        created.setErpStockCode(erpCode);
        created.setErpStockName(erpName);
        warehouseService.addWarehouse(created);
        return true;
    }

    private void renameIfPresent(String warehouseCode, String newName) {
        WmsWarehouse existing = findByWarehouseCode(warehouseCode);
        if (existing == null) {
            log.warn("本地档案初始化：仓库 {} 不存在，跳过改名", warehouseCode);
            return;
        }
        existing.setWarehouseName(newName);
        warehouseService.editWarehouse(existing);
    }

    private void createIfAbsent(String warehouseCode, String name) {
        if (findByWarehouseCode(warehouseCode) != null) {
            return;
        }
        WmsWarehouse created = new WmsWarehouse();
        created.setWarehouseCode(warehouseCode);
        created.setWarehouseName(name);
        created.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_NORMAL);
        created.setStatus("1");
        warehouseService.addWarehouse(created);
    }

    private WmsWarehouse findByWarehouseCode(String warehouseCode) {
        return warehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getWarehouseCode, warehouseCode)
                .last("limit 1"));
    }

    private WmsWarehouse findByErpStockCode(String erpCode) {
        return warehouseMapper.selectOne(new LambdaQueryWrapper<WmsWarehouse>()
                .eq(WmsWarehouse::getErpStockCode, erpCode)
                .last("limit 1"));
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void logSyncSuccess(String filter, String summary, long costMs) {
        syncLogService.logSync("ERP->WMS", SYNC_OBJECT_TYPE, "BD_STOCK", SYNC_TARGET,
                true, 0, filter, summary, null, costMs);
    }

    private void logSyncFailed(String filter, String error, long costMs) {
        syncLogService.logSync("ERP->WMS", SYNC_OBJECT_TYPE, "BD_STOCK", SYNC_TARGET,
                false, 0, filter, null, error, costMs);
    }
}
