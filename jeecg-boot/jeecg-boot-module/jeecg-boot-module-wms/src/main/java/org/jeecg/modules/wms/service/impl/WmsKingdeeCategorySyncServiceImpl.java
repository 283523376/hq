package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.service.IWmsKingdeeCategorySyncService;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
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

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsMaterialCategoryService categoryService;
    private final WmsMaterialCategoryMapper categoryMapper;
    private final IWmsSyncLogService syncLogService;

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
        Map<String, String> rules = parseRules(properties.getMasterData().getCategoryMap());
        if (rules.isEmpty()) {
            vo.setMessage("分类映射未配置（外部依赖 E2），跳过");
            return vo;
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
        syncLogService.logSync("ERP->WMS", SYNC_OBJECT_TYPE, "BD_MATERIAL", SYNC_TARGET,
                true, 0, rules.toString(), vo.getMessage(), null, System.currentTimeMillis() - start);
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
        return true;
    }

    private static String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
