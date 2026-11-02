package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsKingdeeBin;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsKingdeeBinMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeBinSyncService;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 金蝶仓位属性同步实现。
 *
 * <p>数据来源：`BD_FLEXVALUES`（仓位值集；分录 `FEntity` 下挂仓位值）。
 * 口径（2026-10-28 用户明确）：
 * <ul>
 *   <li>金蝶仓位是**逻辑库位/分类属性**，不是物理货位，与 WMS 货位不是 1:1；</li>
 *   <li>同步只建立**属性字典**，货位挂不挂由人工/后续功能决定；</li>
 *   <li>金蝶侧停用的仓位**不删**：被 WMS 货位引用且有库存 → 标「待处理」，否则标「金蝶已停用」。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Service
public class WmsKingdeeBinSyncServiceImpl implements IWmsKingdeeBinSyncService {

    private static final String FORM_ID = "BD_FLEXVALUES";
    private static final String SYNC_OBJECT_TYPE = "KINGDEE_BIN";

    private static final String STATUS_NORMAL = "正常";
    private static final String STATUS_DISABLED = "金蝶已停用";
    private static final String STATUS_PENDING = "待处理";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsKingdeeBinMapper binMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final IWmsSyncLogService syncLogService;

    /** 可选依赖：既有单测按旧构造器 new 服务，注入不到时为 null。 */
    @Autowired(required = false)
    private IWmsAuditLogService auditLogService;

    public WmsKingdeeBinSyncServiceImpl(WmsKingdeeFormClient formClient,
                                        WmsKingdeeProperties properties,
                                        WmsKingdeeBinMapper binMapper,
                                        WmsLocationMapper locationMapper,
                                        WmsInventoryMapper inventoryMapper,
                                        IWmsSyncLogService syncLogService) {
        this.formClient = formClient;
        this.properties = properties;
        this.binMapper = binMapper;
        this.locationMapper = locationMapper;
        this.inventoryMapper = inventoryMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    public WmsKingdeeStockSyncResultVO syncBins() {
        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        if (!properties.isEnabled()) {
            return skip(vo, "金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
        }
        List<String> orgs = properties.getBinOrgNumbers();
        if (orgs == null || orgs.isEmpty()) {
            orgs = properties.getDefaultOrgNumber() == null
                    ? List.of() : List.of(properties.getDefaultOrgNumber());
        }
        if (orgs.isEmpty()) {
            return skip(vo, "未配置仓位同步组织（bin-org-numbers / default-org-number），跳过");
        }

        long start = System.currentTimeMillis();
        int fetched = 0;
        int created = 0;
        int updated = 0;
        Set<String> seen = new HashSet<>();
        for (String org : orgs) {
            List<WmsKingdeeBin> bins = fetchBins(org);
            fetched += bins.size();
            for (WmsKingdeeBin bin : bins) {
                seen.add(key(org, bin.getFlexNumber(), bin.getBinNumber()));
                if (upsert(org, bin)) {
                    created++;
                } else {
                    updated++;
                }
            }
        }
        int suspended = markAbsent(seen);

        vo.setFetched(fetched);
        vo.setCreated(created);
        vo.setRenamed(updated);
        vo.setMessage(String.format("仓位属性：拉取 %d 条（组织 %s），新建 %d，更新 %d，标记停用/待处理 %d",
                fetched, orgs, created, updated, suspended));
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, fetched,
                orgs.toString(), vo.getMessage(), null, System.currentTimeMillis() - start);
        return vo;
    }

    private WmsKingdeeStockSyncResultVO skip(WmsKingdeeStockSyncResultVO vo, String message) {
        log.info("仓位属性同步跳过：{}", message);
        vo.setMessage(message);
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, null, message, null, 0L);
        return vo;
    }

    /** 拉某组织的全部仓位值：先查值集，再逐个 View 取值。 */
    private List<WmsKingdeeBin> fetchBins(String org) {
        List<WmsKingdeeBin> result = new ArrayList<>();
        WmsKingdeeResult sets = formClient.executeBillQuery(FORM_ID, "FNUMBER,FUseOrgId.FNumber",
                "FUseOrgId.FNumber='" + org.replace("'", "''") + "'", 100);
        if (!sets.isSuccess()) {
            throw new RuntimeException("金蝶仓位值集查询失败：" + sets.getMessage());
        }
        for (List<Object> row : sets.getRows()) {
            String flexNumber = row.isEmpty() || row.get(0) == null ? "" : String.valueOf(row.get(0)).trim();
            if (flexNumber.isEmpty()) {
                continue;
            }
            result.addAll(viewValues(flexNumber));
        }
        return result;
    }

    /** View 值集，解析 FLEXVALUESENTRY[]（Number/Name/Id/Forbid）。 */
    @SuppressWarnings("unchecked")
    private List<WmsKingdeeBin> viewValues(String flexNumber) {
        List<WmsKingdeeBin> result = new ArrayList<>();
        WmsKingdeeResult view = formClient.view(FORM_ID, flexNumber);
        if (!view.isSuccess() || oConvertUtils.isEmpty(view.getRaw())) {
            log.warn("值集[{}]查看失败：{}", flexNumber, view.getMessage());
            return result;
        }
        try {
            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(view.getRaw(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object res = root.get("Result");
            Object model = res instanceof Map ? ((Map<?, ?>) res).get("Result") : null;
            Object entries = model instanceof Map ? ((Map<?, ?>) model).get("FLEXVALUESENTRY") : null;
            if (!(entries instanceof List)) {
                return result;
            }
            for (Object item : (List<?>) entries) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) item;
                String number = row.get("Number") == null ? "" : String.valueOf(row.get("Number")).trim();
                if (number.isEmpty()) {
                    continue;
                }
                WmsKingdeeBin bin = new WmsKingdeeBin();
                bin.setFlexNumber(flexNumber);
                bin.setBinNumber(number);
                bin.setBinName(extractName(row.get("Name")));
                Object id = row.get("Id");
                bin.setFlexId(id == null ? null : Long.valueOf(String.valueOf(id)));
                Object forbid = row.get("Forbid");
                bin.setSyncStatus(Boolean.TRUE.equals(forbid) || "true".equalsIgnoreCase(String.valueOf(forbid))
                        ? STATUS_DISABLED : STATUS_NORMAL);
                result.add(bin);
            }
        } catch (Exception e) {
            log.warn("解析值集[{}]失败：{}", flexNumber, e.getMessage());
        }
        return result;
    }

    /**
     * 取简体中文名称：金蝶 View 的 Name 是 `[{Key:2052,Value:"A"},{Key:1054,Value:"A"}]` 数组，
     * 不是按语言键的 Map（2026-10-28 真机踩到，按 Map 解析会把整段 List 串进名称里）。
     */
    private String extractName(Object name) {
        if (name == null) {
            return null;
        }
        if (name instanceof List) {
            for (Object item : (List<?>) name) {
                if (item instanceof Map && "2052".equals(String.valueOf(((Map<?, ?>) item).get("Key")))) {
                    return String.valueOf(((Map<?, ?>) item).get("Value"));
                }
            }
            Object first = ((List<?>) name).isEmpty() ? null : ((List<?>) name).get(0);
            return first instanceof Map ? String.valueOf(((Map<?, ?>) first).get("Value")) : null;
        }
        if (name instanceof Map) {
            Object zh = ((Map<?, ?>) name).get("2052");
            return zh != null ? String.valueOf(zh) : String.valueOf(name);
        }
        return String.valueOf(name);
    }

    /** @return true=新建，false=更新 */
    private boolean upsert(String org, WmsKingdeeBin source) {
        WmsKingdeeBin existing = binMapper.selectOne(new LambdaQueryWrapper<WmsKingdeeBin>()
                .eq(WmsKingdeeBin::getOrgNumber, org)
                .eq(WmsKingdeeBin::getFlexNumber, source.getFlexNumber())
                .eq(WmsKingdeeBin::getBinNumber, source.getBinNumber())
                .last("LIMIT 1"));
        Date now = new Date();
        if (existing == null) {
            source.setOrgNumber(org);
            source.setLastSyncTime(now);
            source.setDeletedAt(0L);
            binMapper.insert(source);
            WmsAuditRecorder.recordMasterData(auditLogService, source.getBinNumber(),
                    WmsConstants.AUDIT_ACTION_SYNC_IN, source);
            return true;
        }
        existing.setBinName(source.getBinName());
        existing.setFlexId(source.getFlexId());
        // 金蝶侧恢复可用 → 状态回到正常；金蝶停用但本地是「待处理」的保持待处理，交人工
        if (STATUS_NORMAL.equals(source.getSyncStatus())) {
            existing.setSyncStatus(STATUS_NORMAL);
        } else if (!STATUS_PENDING.equals(existing.getSyncStatus())) {
            existing.setSyncStatus(STATUS_DISABLED);
        }
        existing.setLastSyncTime(now);
        binMapper.updateById(existing);
        WmsAuditRecorder.recordMasterData(auditLogService, existing.getBinNumber(),
                WmsConstants.AUDIT_ACTION_UPDATE, existing);
        return false;
    }

    /**
     * 金蝶侧已不存在的仓位：不删除，按「是否被有库存的货位引用」标记状态。
     *
     * @return 被标记的条数
     */
    private int markAbsent(Set<String> seen) {
        List<WmsKingdeeBin> all = binMapper.selectList(new LambdaQueryWrapper<WmsKingdeeBin>()
                .last("LIMIT 5000"));
        int marked = 0;
        for (WmsKingdeeBin bin : all) {
            if (seen.contains(key(bin.getOrgNumber(), bin.getFlexNumber(), bin.getBinNumber()))) {
                continue;
            }
            if (STATUS_DISABLED.equals(bin.getSyncStatus()) || STATUS_PENDING.equals(bin.getSyncStatus())) {
                continue;
            }
            String status = referencedWithStock(bin.getId()) ? STATUS_PENDING : STATUS_DISABLED;
            WmsKingdeeBin patch = new WmsKingdeeBin();
            patch.setId(bin.getId());
            patch.setSyncStatus(status);
            patch.setLastSyncTime(new Date());
            binMapper.updateById(patch);
            WmsAuditRecorder.recordMasterData(auditLogService, bin.getBinNumber(),
                    WmsConstants.AUDIT_ACTION_UPDATE, bin);
            marked++;
            log.warn("仓位属性[{}/{}]在金蝶侧已不存在，标记为「{}」", bin.getFlexNumber(), bin.getBinNumber(), status);
        }
        return marked;
    }

    /** 是否被 WMS 货位引用且该货位有库存（有库存不允许直接停用）。 */
    private boolean referencedWithStock(String binId) {
        List<WmsLocation> locations = locationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getKingdeeBinId, binId));
        for (WmsLocation location : locations) {
            Long count = inventoryMapper.selectCount(new LambdaQueryWrapper<WmsInventory>()
                    .eq(WmsInventory::getLocationId, location.getId())
                    .gt(WmsInventory::getQuantity, BigDecimal.ZERO));
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
    }

    private String key(String org, String flexNumber, String binNumber) {
        return org + "|" + flexNumber + "|" + binNumber;
    }
}
