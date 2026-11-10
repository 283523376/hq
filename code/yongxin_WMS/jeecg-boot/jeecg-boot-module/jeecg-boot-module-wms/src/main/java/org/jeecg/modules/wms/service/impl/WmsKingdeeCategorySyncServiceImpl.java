package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeCategorySyncService;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialGroupMapService;
import org.jeecg.modules.wms.audit.WmsAuditRecorder;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 物料分组 → 物料分类映射同步。
 *
 * <p>映射是**多对一**：一个 WMS 分类可对应多个金蝶分组，关系活在配置里
 * （{@code category-map} 的「前缀=分类编码」规则），分类行上的
 * {@code erp_group_number/name} 只记首个命中的**代表**分组，供人工核对。
 *
 * <p>分类类型（原材料/半成品/成品/辅料）自 2026-11-05 起取自映射表
 * {@code wms_material_group_map}（{@link IWmsMaterialGroupMapService}），
 * 不再读系统配置 {@code kingdee.material-group-map}。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Service
public class WmsKingdeeCategorySyncServiceImpl implements IWmsKingdeeCategorySyncService {

    private static final String FORM_ID = "BD_MATERIAL";
    private static final String FIELD_KEYS =
            "FNumber,FName,FMaterialGroup.FNumber,FMaterialGroup.FName";
    /** 单页条数；返回行数小于该值即视为末页。 */
    private static final int PAGE_SIZE = 500;
    /** 翻页硬上限，防止金蝶异常时死循环。 */
    private static final int MAX_PAGES = 200;

    private static final String SYNC_OBJECT_TYPE = "MATERIAL_CATEGORY";
    private static final String SYNC_TARGET = "WMS";

    /** 分组同步只取两列：分组编码 + 分组名称。 */
    private static final String GROUP_FIELD_KEYS = "FMaterialGroup.FNumber,FMaterialGroup.FName";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsMaterialCategoryService categoryService;
    private final WmsMaterialCategoryMapper categoryMapper;
    private final IWmsSyncLogService syncLogService;

    /** 可选依赖：既有单测按旧构造器 new 服务，注入不到时为 null。 */
    @Autowired(required = false)
    private IWmsAuditLogService auditLogService;

    /** 可选依赖（同上）：为 null 时视为「未配置映射」，分类类型留空。 */
    @Autowired(required = false)
    private IWmsMaterialGroupMapService materialGroupMapService;

    /** 可选依赖（同上）：为 null 时不做「是否已配置」前置检查（单测裸构造场景）。 */
    @Autowired(required = false)
    private IWmsKingdeeAuthService kingdeeAuthService;

    public WmsKingdeeCategorySyncServiceImpl(WmsKingdeeFormClient formClient,
                                             WmsKingdeeProperties properties,
                                             IWmsMaterialCategoryService categoryService,
                                             WmsMaterialCategoryMapper categoryMapper,
                                             IWmsSyncLogService syncLogService) {
        this.formClient = formClient;
        this.properties = properties;
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
        this.syncLogService = syncLogService;
    }

    @Override
    public WmsKingdeeStockSyncResultVO syncCategory() {
        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        if (!properties.isEnabled()) {
            return skip(vo, "金蝶同步总开关 wms.sync.erp.kingdee.enabled=false，跳过");
        }
        Map<String, String> rules = parseRules(properties.getMasterData().getCategoryMap());
        if (rules.isEmpty()) {
            return skip(vo, "分类映射未配置（外部依赖 E2），跳过");
        }

        long start = System.currentTimeMillis();
        List<List<Object>> rows = fetchAllMaterials();
        vo.setFetched(rows.size());

        // 每条规则只取首个命中的分组作为代表
        Map<String, List<Object>> representative = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            if (row.size() < 4) {
                continue;
            }
            String groupNumber = asText(row.get(2));
            if (groupNumber.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> rule : rules.entrySet()) {
                if (groupNumber.startsWith(rule.getKey()) && !representative.containsKey(rule.getValue())) {
                    representative.put(rule.getValue(), row);
                }
            }
        }

        for (Map.Entry<String, List<Object>> entry : representative.entrySet()) {
            if (stampCategory(entry.getKey(), entry.getValue())) {
                vo.setRenamed(vo.getRenamed() + 1);
            }
        }
        vo.setMessage(String.format("扫描物料 %d 行，更新分类 %d 个", vo.getFetched(), vo.getRenamed()));
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                "BD_MATERIAL", WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0,
                rules.toString(), vo.getMessage(), null, System.currentTimeMillis() - start);
        return vo;
    }

    @Override
    public WmsKingdeeStockSyncResultVO syncMaterialGroups() {
        // 显式失败：配置问题不能伪装成「同步完成，0 个」
        if (!properties.isEnabled()) {
            throw new RuntimeException("金蝶同步总开关 wms.sync.erp.kingdee.enabled 未开启，无法同步物料分组");
        }
        if (kingdeeAuthService != null && !kingdeeAuthService.isConfigured()) {
            throw new RuntimeException("金蝶未配置（baseUrl/acctId/appId 等），无法同步物料分组");
        }

        long start = System.currentTimeMillis();
        GroupFetchResult fetched = fetchAllGroups();
        Map<String, String> typeByGroup = parseTypeMapping();

        WmsKingdeeStockSyncResultVO vo = new WmsKingdeeStockSyncResultVO();
        vo.setFetched(fetched.scannedRows);
        for (Map.Entry<String, String> group : fetched.groups.entrySet()) {
            UpsertResult result = upsertGroupCategory(group.getKey(), group.getValue(),
                    typeByGroup.get(group.getKey()));
            if (result == UpsertResult.CREATED) {
                vo.setCreated(vo.getCreated() + 1);
            } else if (result == UpsertResult.UPDATED) {
                vo.setRenamed(vo.getRenamed() + 1);
            }
        }
        vo.setMessage(String.format("扫描金蝶物料 %d 行，识别分组 %d 个：新建 %d，更新 %d",
                vo.getFetched(), fetched.groups.size(), vo.getCreated(), vo.getRenamed()));
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, vo.getFetched(),
                "groups=" + fetched.groups.size(), vo.getMessage(), null,
                System.currentTimeMillis() - start);
        return vo;
    }

    /**
     * 从映射表读取分组 → 分类类型（方案 A，2026-11-05）。
     *
     * <p>不再读系统配置 {@code kingdee.material-group-map}：{@code config_value} 只有
     * varchar(500)，装不下 2039 个分组。映射服务缺失（单测裸构造）或表为空都返回空映射，
     * 此时分类类型留 NULL，不阻断同步——这是合法初始态，不是错误。
     */
    private Map<String, String> parseTypeMapping() {
        if (materialGroupMapService == null) {
            return new LinkedHashMap<>();
        }
        return materialGroupMapService.typeByGroupNumber();
    }

    /**
     * 单分组 upsert。按 {@code erp_group_number} 命中；为兼容历史手工分类（编码相同但没填
     * erp_group_number），再按 {@code category_code} 兜底，避免撞唯一索引。
     */
    private UpsertResult upsertGroupCategory(String groupNumber, String groupName, String mappedType) {
        String safeName = truncate(oConvertUtils.isEmpty(groupName) ? groupNumber : groupName, 100);
        String safeErpName = truncate(groupName, 200);
        WmsMaterialCategory existing = categoryMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialCategory>()
                        .and(w -> w.eq(WmsMaterialCategory::getErpGroupNumber, groupNumber)
                                .or().eq(WmsMaterialCategory::getCategoryCode, groupNumber))
                        .last("limit 1"));
        if (existing == null) {
            WmsMaterialCategory category = new WmsMaterialCategory()
                    .setCategoryCode(groupNumber)
                    .setCategoryName(safeName)
                    .setParentId(WmsConstants.ROOT_PID_VALUE)
                    // 未配置映射时留 NULL：不猜「原材料」，避免把未分类物料错误路由
                    .setCategoryType(mappedType)
                    .setErpGroupNumber(groupNumber)
                    .setErpGroupName(safeErpName)
                    .setStatus(WmsConstants.STATUS_ENABLE)
                    .setHasChild(WmsConstants.NO)
                    .setDeletedAt(0L);
            WmsUtils.fillCreate(category);
            categoryMapper.insert(category);
            WmsAuditRecorder.recordMasterData(auditLogService, groupNumber,
                    WmsConstants.AUDIT_ACTION_SYNC_IN, category);
            return UpsertResult.CREATED;
        }

        boolean changed = false;
        if (!safeName.equals(existing.getCategoryName())) {
            existing.setCategoryName(safeName);
            changed = true;
        }
        if (!groupNumber.equals(existing.getErpGroupNumber())) {
            existing.setErpGroupNumber(groupNumber);
            changed = true;
        }
        if (!safeErpName.equals(existing.getErpGroupName())) {
            existing.setErpGroupName(safeErpName);
            changed = true;
        }
        // 映射不到时不动已有 category_type：人工维护的值不能被一次同步清空
        if (oConvertUtils.isNotEmpty(mappedType) && !mappedType.equals(existing.getCategoryType())) {
            existing.setCategoryType(mappedType);
            changed = true;
        }
        if (!changed) {
            return UpsertResult.UNCHANGED;
        }
        WmsUtils.fillUpdate(existing);
        categoryMapper.updateById(existing);
        WmsAuditRecorder.recordMasterData(auditLogService, groupNumber,
                WmsConstants.AUDIT_ACTION_UPDATE, existing);
        return UpsertResult.UPDATED;
    }

    /**
     * 拉取全量物料，按 {@code FMaterialGroup.FNumber} 去重成分组清单。
     *
     * <p>物料同步沿用同一取数方式（每页 500 行，返回不足一页即末页）；50000 条物料
     * 对应约 100 页，2996 个分组。若改成按分组表查询，需先确认 BD_MATERIALGROUP 的
     * 组织过滤字段，当前复用已验证的路径更稳妥。
     */
    private GroupFetchResult fetchAllGroups() {
        String orgId = properties.getMasterData().getOrgId();
        String filter = (orgId == null || orgId.trim().isEmpty())
                ? "" : "FUseOrgId.FNumber='" + orgId.trim().replace("'", "''") + "'";
        Map<String, String> groups = new LinkedHashMap<>();
        int scanned = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            WmsKingdeeResult result = formClient.executeBillQuery(
                    FORM_ID, GROUP_FIELD_KEYS, filter, PAGE_SIZE, page * PAGE_SIZE);
            if (!result.isSuccess()) {
                throw new RuntimeException("金蝶物料分组查询失败：" + result.getMessage());
            }
            List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            scanned += rows.size();
            for (List<Object> row : rows) {
                String groupNumber = asText(row.size() > 0 ? row.get(0) : null);
                if (groupNumber.isEmpty()) {
                    continue;
                }
                if (groupNumber.length() > 64) {
                    log.warn("金蝶物料分组编码超长（>64），已跳过：{}", groupNumber);
                    continue;
                }
                String groupName = asText(row.size() > 1 ? row.get(1) : null);
                groups.putIfAbsent(groupNumber, groupName);
            }
            if (rows.size() < PAGE_SIZE) {
                return new GroupFetchResult(scanned, groups);
            }
        }
        log.warn("物料分组同步达到翻页上限 {} 页，已截断", MAX_PAGES);
        return new GroupFetchResult(scanned, groups);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 分组拉取结果：扫描行数（结果口径）+ 去重后的分组。 */
    private static final class GroupFetchResult {
        private final int scannedRows;
        private final Map<String, String> groups;

        private GroupFetchResult(int scannedRows, Map<String, String> groups) {
            this.scannedRows = scannedRows;
            this.groups = groups;
        }
    }

    /** 单分组 upsert 结果，用于区分新建/更新/无变化。 */
    private enum UpsertResult {
        CREATED, UPDATED, UNCHANGED
    }

    /** 跳过分支也留日志，便于回查定时任务是否真的跑过。 */
    private WmsKingdeeStockSyncResultVO skip(WmsKingdeeStockSyncResultVO vo, String message) {
        log.info("分类同步跳过：{}", message);
        vo.setMessage(message);
        syncLogService.logSync(WmsConstants.SYNC_LOG_DIRECTION_ERP_TO_WMS, SYNC_OBJECT_TYPE,
                "BD_MATERIAL", WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0, null, message, null, 0L);
        return vo;
    }

    /** 解析「前缀=分类编码」规则；格式不对的条目跳过并告警。 */
    static Map<String, String> parseRules(List<String> categoryMap) {
        Map<String, String> rules = new LinkedHashMap<>();
        if (categoryMap == null) {
            return rules;
        }
        for (String raw : categoryMap) {
            if (raw == null) {
                continue;
            }
            int idx = raw.indexOf('=');
            if (idx <= 0 || idx == raw.length() - 1) {
                log.warn("分类映射规则格式非法，已跳过：{}", raw);
                continue;
            }
            rules.put(raw.substring(0, idx).trim(), raw.substring(idx + 1).trim());
        }
        return rules;
    }

    private List<List<Object>> fetchAllMaterials() {
        String orgId = properties.getMasterData().getOrgId();
        String filter = (orgId == null || orgId.trim().isEmpty())
                ? "" : "FUseOrgId.FNumber='" + orgId.trim().replace("'", "''") + "'";
        List<List<Object>> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            WmsKingdeeResult result = formClient.executeBillQuery(
                    FORM_ID, FIELD_KEYS, filter, PAGE_SIZE, page * PAGE_SIZE);
            if (!result.isSuccess()) {
                throw new RuntimeException("金蝶物料查询失败：" + result.getMessage());
            }
            List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            all.addAll(rows);
            if (rows.size() < PAGE_SIZE) {
                return all;
            }
        }
        log.warn("物料分组同步达到翻页上限 {} 页，已截断", MAX_PAGES);
        return all;
    }

    private boolean stampCategory(String categoryCode, List<Object> row) {
        WmsMaterialCategory category = categoryMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialCategory>()
                        .eq(WmsMaterialCategory::getCategoryCode, categoryCode)
                        .last("limit 1"));
        if (category == null) {
            log.warn("分类映射指向不存在的分类编码 {}，跳过", categoryCode);
            return false;
        }
        category.setErpGroupNumber(asText(row.get(2)));
        category.setErpGroupName(asText(row.get(3)));
        categoryService.editCategory(category);
        WmsAuditRecorder.recordMasterData(auditLogService, category.getCategoryCode(),
                WmsConstants.AUDIT_ACTION_UPDATE, category);
        return true;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
