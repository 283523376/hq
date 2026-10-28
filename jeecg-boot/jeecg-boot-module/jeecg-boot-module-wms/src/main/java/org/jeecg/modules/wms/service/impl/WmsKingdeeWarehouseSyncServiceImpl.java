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
import java.util.Set;
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
    /** 单页条数（全量同步 101 组织约 200 个仓，一页足够，保留翻页防增长）。 */
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 20;

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
        if (!properties.isEnabled()) {
            return skip("金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
        }
        // 组织过滤是硬约束：留空等于取消过滤，跨组织同码会互相覆盖 → 视为未配置
        if (masterData.getOrgId() == null || masterData.getOrgId().trim().isEmpty()) {
            return skip("组织未配置（master-data.org-id 为空），拒绝无组织过滤的查询，跳过");
        }
        List<String> whitelist = effectiveWhitelist(masterData.getStockWhitelist());
        String filter = whitelist.isEmpty()
                ? "FUseOrgId.FNumber='" + masterData.getOrgId().trim().replace("'", "''") + "'"
                : buildFilter(masterData.getOrgId(), whitelist);

        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        long start = System.currentTimeMillis();
        List<List<Object>> rows = fetchAllStocks(filter, start);
        vo.setFetched(rows.size());
        Map<String, String> codeMap = masterData.getStockCodeMap();
        Set<String> externals = effectiveExternalCodes(masterData.getExternalStockCodes());
        int external = 0;
        for (List<Object> row : rows) {
            if (row.size() < 2) {
                continue;
            }
            String erpCode = asText(row.get(0));
            String erpName = asText(row.get(1));
            if (erpCode.isEmpty()) {
                continue;
            }
            boolean isExternal = externals.contains(erpCode);
            if (isExternal) {
                external++;
            }
            String mappedCode = codeMap == null ? null : codeMap.get(erpCode);
            if (mappedCode != null && !mappedCode.isEmpty()) {
                if (renameMapped(mappedCode, erpCode, erpName)) {
                    vo.setRenamed(vo.getRenamed() + 1);
                }
                if (isExternal) {
                    markExternal(mappedCode);
                }
            } else if (createOrUpdateByErpCode(erpCode, erpName, isExternal)) {
                vo.setCreated(vo.getCreated() + 1);
            }
        }
        vo.setMessage(String.format("拉取 %d 个仓库（含外部仓 %d）：改名 %d，新建 %d",
                vo.getFetched(), external, vo.getRenamed(), vo.getCreated()));
        logSyncSuccess(filter, vo.getMessage(), System.currentTimeMillis() - start);
        return vo;
    }

    /** 翻页拉全量：金蝶 ExecuteBillQuery 的翻页靠 Limit（TopRowCount 会先截断结果集）。 */
    private List<List<Object>> fetchAllStocks(String filter, long start) {
        List<List<Object>> all = new java.util.ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            WmsKingdeeResult result = formClient.executeBillQuery(
                    FORM_ID, FIELD_KEYS, filter, PAGE_SIZE, page * PAGE_SIZE);
            if (!result.isSuccess()) {
                logSyncFailed(filter, result.getMessage(), System.currentTimeMillis() - start);
                throw new RuntimeException("金蝶仓库查询失败：" + result.getMessage());
            }
            List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            all.addAll(rows);
            if (rows.size() < PAGE_SIZE) {
                return all;
            }
        }
        log.warn("仓库同步达到翻页上限 {} 页，已截断", MAX_PAGES);
        return all;
    }

    /**
     * 已废弃：WMS 仓库编码与金蝶 1:1（2026-10-28 决策）后，不再允许自建本地仓库档案。
     *
     * <p>原实现会新建 CJ/TC 并把 LB/MJ 改名，这些编码在金蝶里不存在，会破坏 1:1 口径。
     * 保留空实现只为兼容既有调用方（前端按钮/定时任务），调用时只记一条提示。
     */
    @Override
    public void initLocalArchives() {
        log.warn("初始化本地档案已废弃：WMS 仓库编码必须与金蝶 BD_STOCK.FNumber 1:1，"
                + "请改用「同步金蝶仓库」；本次调用未做任何改动");
    }

    /** 去掉空白项后的白名单；全空白 = 未配置 = 全量同步。 */
    static List<String> effectiveWhitelist(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList());
    }

    /** 外部仓编码集合（去空白、大写不敏感比较由调用方保证原样匹配）。 */
    static Set<String> effectiveExternalCodes(List<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        return raw.stream()
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    /** 把已映射的 WMS 仓标成外部仓（其余属性不动）。 */
    private void markExternal(String warehouseCode) {
        WmsWarehouse existing = findByWarehouseCode(warehouseCode);
        if (existing == null) {
            return;
        }
        if (!WmsConstants.WAREHOUSE_TYPE_EXTERNAL.equals(existing.getWarehouseType())) {
            existing.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_EXTERNAL);
            warehouseService.editWarehouse(existing);
        }
    }

    /** 组织 + 白名单过滤。组织过滤是硬约束：同码不同组织是两份档案。 */
    static String buildFilter(String orgId, List<String> whitelist) {
        String codes = whitelist.stream()
                .filter(code -> code != null && !code.trim().isEmpty())
                .map(code -> "'" + code.trim().replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        if (codes.isEmpty()) {
            // 调用方已保证不走到这里；真走到了宁可报错也不要退化成无白名单的全量查询
            throw new IllegalStateException("仓库同步滤条件缺少白名单编码");
        }
        String orgPart = (orgId != null && !orgId.trim().isEmpty())
                ? "FUseOrgId.FNumber='" + orgId.trim().replace("'", "''") + "' AND "
                : "";
        return orgPart + "FNumber in (" + codes + ")";
    }

    /** 命中映射：只改名称与 ERP 列，仓库编码保持不变。 */
    private boolean renameMapped(String warehouseCode, String erpCode, String erpName) {
        WmsWarehouse existing = findByWarehouseCode(warehouseCode);
        if (existing == null) {
            log.warn("仓库映射 {} -> {} 指向不存在的 WMS 仓库，跳过", erpCode, warehouseCode);
            return false;
        }
        // 该金蝶编码可能已被「早先未配置映射时自动建出的仓」占用；
        // 直接盖过去会撞 uk_wms_warehouse_erp，报的是 MySQL 原始错误，运维看不出是映射冲突
        WmsWarehouse squatter = findByErpStockCode(erpCode);
        if (squatter != null && !squatter.getId().equals(existing.getId())) {
            throw new RuntimeException(String.format(
                    "金蝶仓库编码 %s 已被 WMS 仓库 %s（id=%s）占用，无法映射到 %s；"
                            + "请先停用/清理该仓或调整 stock-code-map 后重试",
                    erpCode, squatter.getWarehouseCode(), squatter.getId(), warehouseCode));
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
    private boolean createOrUpdateByErpCode(String erpCode, String erpName, boolean external) {
        WmsWarehouse existing = findByErpStockCode(erpCode);
        if (existing == null) {
            existing = findByWarehouseCode(erpCode);
        }
        if (existing != null) {
            existing.setWarehouseName(erpName);
            existing.setErpStockCode(erpCode);
            existing.setErpStockName(erpName);
            if (external) {
                existing.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_EXTERNAL);
            }
            warehouseService.editWarehouse(existing);
            return false;
        }
        WmsWarehouse created = new WmsWarehouse();
        created.setWarehouseCode(erpCode);
        created.setWarehouseName(erpName);
        created.setWarehouseType(external
                ? WmsConstants.WAREHOUSE_TYPE_EXTERNAL : WmsConstants.WAREHOUSE_TYPE_NORMAL);
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

    /** 跳过（开关未开/白名单或组织未配）：也留一条成功日志，便于回查「昨晚到底跑没跑」。 */
    private WmsKingdeeStockSyncResultVO skip(String message) {
        log.info("仓库同步跳过：{}", message);
        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        vo.setMessage(message);
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                "BD_STOCK", WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, null, message, null, 0L);
        return vo;
    }

    private void logSyncSuccess(String filter, String summary, long costMs) {
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                "BD_STOCK", WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, filter, summary, null, costMs);
    }

    private void logSyncFailed(String filter, String error, long costMs) {
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                "BD_STOCK", WmsConstants.SYNC_LOG_TARGET_KINGDEE, false, 0, filter, null, error, costMs);
    }
}
