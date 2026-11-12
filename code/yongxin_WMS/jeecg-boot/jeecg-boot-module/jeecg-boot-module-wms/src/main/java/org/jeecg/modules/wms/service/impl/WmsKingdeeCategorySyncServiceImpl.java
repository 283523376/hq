package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsMaterialCategoryGroup;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryGroupMapper;
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
import java.util.Date;
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

    /**
     * 可选依赖（同上）：分组 → 分类的权威映射（2026-11-11 分类合并后 1:N）。
     * 为 null 时退化为「按分类行 erp_group_number 精确命中」的旧行为。
     */
    @Autowired(required = false)
    private WmsMaterialCategoryGroupMapper categoryGroupMapper;

    /**
     * 分组同步的在途标记：HTTP 超时后用户重试、或定时任务与手点撞车时，
     * 第二个请求直接失败，避免并发「查不到 → 新建」造出重复分类。
     */
    private final java.util.concurrent.atomic.AtomicBoolean materialGroupsRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        // 全量拉取约 30s，前端超时后用户会再点一次；两次并发会同时走「查不到 → 新建」，
        // 可能建出重复分类（名称+类型无唯一索引），必须串行化（2026-11-12 实测触发）。
        if (!materialGroupsRunning.compareAndSet(false, true)) {
            throw new RuntimeException("物料分组同步正在进行中，请稍后刷新查看结果");
        }
        try {
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
                    FORM_ID, WmsConstants.SYNC_LOG_TARGET_KINGDEE, true, 0,
                    "groups=" + fetched.groups.size(), vo.getMessage(), null,
                    System.currentTimeMillis() - start);
            return vo;
        } finally {
            materialGroupsRunning.set(false);
        }
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
     * 单分组 upsert。
     *
     * <p>命中顺序（2026-11-11 分类合并起）：
     * <ol>
     *   <li>映射表 {@code wms_material_category_group}——分组已归属某分类，直接复用，**绝不新建**；</li>
     *   <li>分类行 {@code erp_group_number} / {@code category_code} 精确命中（历史行为）；</li>
     *   <li>同名同类型的存活分类——合并后同名分组指向同一条分类，命中即复用并补映射，
     *       这是「不再产生重复分类」的关键；</li>
     *   <li>都没有才新建。</li>
     * </ol>
     */
    private UpsertResult upsertGroupCategory(String groupNumber, String groupName, String mappedType) {
        String safeName = truncate(oConvertUtils.isEmpty(groupName) ? groupNumber : groupName, 100);
        String safeErpName = truncate(groupName, 200);

        WmsMaterialCategory mapped = findMappedCategory(groupNumber);
        if (mapped != null) {
            return applyMappedType(groupNumber, safeName, mapped, mappedType);
        }

        WmsMaterialCategory existing = categoryMapper.selectOne(
                new LambdaQueryWrapper<WmsMaterialCategory>()
                        .and(w -> w.eq(WmsMaterialCategory::getErpGroupNumber, groupNumber)
                                .or().eq(WmsMaterialCategory::getCategoryCode, groupNumber))
                        .last("limit 1"));
        if (existing == null) {
            WmsMaterialCategory sameName = findByNameAndType(safeName, mappedType);
            if (sameName != null) {
                bindGroup(groupNumber, safeName, sameName.getId());
                return applyMappedType(groupNumber, safeName, sameName, mappedType);
            }
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
            bindGroup(groupNumber, safeName, category.getId());
            WmsAuditRecorder.recordMasterData(auditLogService, groupNumber,
                    WmsConstants.AUDIT_ACTION_SYNC_IN, category);
            return UpsertResult.CREATED;
        }

        bindGroup(groupNumber, safeName, existing.getId());

        boolean changed = false;
        if (!safeName.equals(existing.getCategoryName())) {
            // ERP 改分组名时不能盲目改名：若新名称+目标类型已有另一条存活分类，
            // 改名会把刚合并掉的重复再造出来（2026-11-12 审计 R3）→ 保持原名并告警。
            String targetType = oConvertUtils.isNotEmpty(mappedType)
                    ? mappedType : existing.getCategoryType();
            WmsMaterialCategory twin = findByNameAndType(safeName, targetType, existing.getId());
            if (twin == null) {
                existing.setCategoryName(safeName);
                changed = true;
            } else {
                log.warn("分组 {} 的新名称「{}」与分类 {} 同名同类型，保持原名「{}」以避免重复",
                        groupNumber, safeName, twin.getId(), existing.getCategoryName());
            }
        }
        if (!groupNumber.equals(existing.getErpGroupNumber())) {
            existing.setErpGroupNumber(groupNumber);
            changed = true;
        }
        if (!safeErpName.equals(existing.getErpGroupName())) {
            existing.setErpGroupName(safeErpName);
            changed = true;
        }
        // 映射不到时不动已有 category_type：人工维护的值不能被一次同步清空。
        // 改类型前与 applyMappedType 同一口径（2026-11-12 正式 QA P1）：先查「同名+新类型」
        // 是否已有另一条存活分类——映射行被删的分组会落到本分支，触发条件与 R1 同构。
        if (oConvertUtils.isNotEmpty(mappedType) && !mappedType.equals(existing.getCategoryType())) {
            if (repointGroupIfTypeWouldDuplicate(groupNumber, safeName, existing, mappedType)) {
                return UpsertResult.UPDATED;
            }
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
     * 映射表命中：分组已归属某分类则返回该分类；映射指向已删除分类时返回 null
     * （按未知分组处理，避免把物料挂到看不见的分类上）。
     */
    private WmsMaterialCategory findMappedCategory(String groupNumber) {
        if (categoryGroupMapper == null) {
            return null;
        }
        try {
            WmsMaterialCategoryGroup row = categoryGroupMapper.selectOne(
                    new LambdaQueryWrapper<WmsMaterialCategoryGroup>()
                            .eq(WmsMaterialCategoryGroup::getGroupNumber, groupNumber)
                            .last("limit 1"));
            if (row == null || oConvertUtils.isEmpty(row.getCategoryId())) {
                return null;
            }
            WmsMaterialCategory category = categoryMapper.selectById(row.getCategoryId());
            if (category == null) {
                log.warn("分组 {} 的映射指向已删除分类 {}，按未知分组处理", groupNumber, row.getCategoryId());
            }
            return category;
        } catch (Exception e) {
            // 查询失败不能按「未知分组」降级继续：双重未命中时会走到新建分支再造重复
            // （2026-11-12 QA M2）。本轮直接中止——同步幂等，下轮重跑即可。
            throw new RuntimeException("查询分组 " + groupNumber + " 的映射失败，本轮分组同步中止：" + e.getMessage(), e);
        }
    }

    /**
     * 按「名称 + 类型」找已有存活分类——合并键与迁移脚本
     * {@code V3.9.5_60} 一致（{@code IFNULL(category_type,'')}），命中即复用，不再新建。
     */
    private WmsMaterialCategory findByNameAndType(String categoryName, String categoryType) {
        return findByNameAndType(categoryName, categoryType, null);
    }

    /**
     * @param excludeId 排除自身（编辑/改名场景），null 表示不排除；排除在 SQL 层完成
     *                  （{@code ne(id)}），不再有 Java 侧兜底——兜底分支是死代码，还会掩盖
     *                  排除子句被误删的回归（2026-11-12 正式 QA P2）。
     */
    private WmsMaterialCategory findByNameAndType(String categoryName, String categoryType, String excludeId) {
        LambdaQueryWrapper<WmsMaterialCategory> wrapper = new LambdaQueryWrapper<WmsMaterialCategory>()
                .eq(WmsMaterialCategory::getCategoryName, categoryName);
        if (oConvertUtils.isEmpty(categoryType)) {
            wrapper.and(w -> w.isNull(WmsMaterialCategory::getCategoryType)
                    .or().eq(WmsMaterialCategory::getCategoryType, ""));
        } else {
            wrapper.eq(WmsMaterialCategory::getCategoryType, categoryType);
        }
        if (oConvertUtils.isNotEmpty(excludeId)) {
            wrapper.ne(WmsMaterialCategory::getId, excludeId);
        }
        wrapper.orderByAsc(WmsMaterialCategory::getCreateTime).last("limit 1");
        return categoryMapper.selectOne(wrapper);
    }

    /**
     * 改分类类型前的孪生守卫（R1，2026-11-12 正式 QA P1 起两处共用）：
     * 若「同名 + 新类型」已有另一条存活分类，**不改类型**，把分组改指到那条分类并返回
     * {@code true}（调用方据此按 UPDATED 计数并短路——改指了映射行，不能算「无变化」，
     * QA M1）；无孪生返回 {@code false}，调用方继续改类型。
     */
    private boolean repointGroupIfTypeWouldDuplicate(String groupNumber, String groupName,
                                                     WmsMaterialCategory category, String newType) {
        WmsMaterialCategory twin = findByNameAndType(category.getCategoryName(), newType, category.getId());
        if (twin == null) {
            return false;
        }
        log.info("分组 {} 的类型映射为「{}」，分类「{}」({}) 改类型将与分类 {} 同名同类型，改为映射到后者",
                groupNumber, newType, category.getCategoryName(), category.getId(), twin.getId());
        bindGroup(groupNumber, groupName, twin.getId());
        return true;
    }

    /**
     * 复用已有分类时**只补分类类型，不改名称**：合并后的分类名称由存活分类决定，
     * 非代表分组不能反过来给它改名。
     *
     * <p>改类型前走孪生守卫 {@link #repointGroupIfTypeWouldDuplicate}（R1）：
     * 同名同类型已有存活分类时改指过去、不改类型，否则一次映射同步就能把刚合并掉的
     * 重复重新造出来。
     */
    private UpsertResult applyMappedType(String groupNumber, String groupName,
                                         WmsMaterialCategory category, String mappedType) {
        if (oConvertUtils.isEmpty(mappedType) || mappedType.equals(category.getCategoryType())) {
            return UpsertResult.UNCHANGED;
        }
        if (repointGroupIfTypeWouldDuplicate(groupNumber, groupName, category, mappedType)) {
            return UpsertResult.UPDATED;
        }
        category.setCategoryType(mappedType);
        WmsUtils.fillUpdate(category);
        categoryMapper.updateById(category);
        WmsAuditRecorder.recordMasterData(auditLogService, category.getCategoryCode(),
                WmsConstants.AUDIT_ACTION_UPDATE, category);
        return UpsertResult.UPDATED;
    }

    /**
     * 维护「分组号 → 分类」映射。写失败只告警不阻断同步：
     * 下一轮同步会按名称/类型再次命中同一分类，不会因此产生重复分类。
     *
     * <p>{@code create_by} 记当前账号（定时任务下为 null）；迁移脚本写入的行是 {@code 'merge'}，
     * 回滚脚本据此区分。
     */
    private void bindGroup(String groupNumber, String groupName, String categoryId) {
        if (categoryGroupMapper == null || oConvertUtils.isEmpty(categoryId)) {
            return;
        }
        try {
            WmsMaterialCategoryGroup existing = categoryGroupMapper.selectOne(
                    new LambdaQueryWrapper<WmsMaterialCategoryGroup>()
                            .eq(WmsMaterialCategoryGroup::getGroupNumber, groupNumber)
                            .last("limit 1"));
            if (existing == null) {
                categoryGroupMapper.insert(new WmsMaterialCategoryGroup()
                        .setGroupNumber(groupNumber)
                        .setGroupName(truncate(groupName, 100))
                        .setCategoryId(categoryId)
                        .setDeletedAt(0L)
                        .setCreateBy(WmsUtils.currentUsername())
                        .setCreateTime(new Date()));
            } else if (!categoryId.equals(existing.getCategoryId())) {
                existing.setCategoryId(categoryId);
                existing.setGroupName(truncate(groupName, 100));
                categoryGroupMapper.updateById(existing);
            }
        } catch (Exception e) {
            log.warn("写入分组→分类映射失败（{}）：{}", groupNumber, e.getMessage());
        }
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
