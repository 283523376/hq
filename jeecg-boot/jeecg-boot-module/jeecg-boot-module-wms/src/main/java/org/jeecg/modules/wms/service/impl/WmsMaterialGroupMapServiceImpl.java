package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsMaterialGroupMap;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialGroupMapMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsMaterialGroupMapService;
import org.jeecg.modules.wms.util.WmsMaterialGroupMapping;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsMaterialGroupMapSaveResultVO;
import org.jeecg.modules.wms.vo.WmsUnmappedGroupVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶物料分组 → WMS 分类类型映射（方案 A：独立映射表）。
 *
 * <p>批量保存的硬约束：**先全量校验、再落库**。任一分类类型非法即整批拒绝，
 * 避免出现「前 3 条生效、第 4 条失败」这种用户无法判断最终状态的结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Slf4j
@Service
public class WmsMaterialGroupMapServiceImpl extends ServiceImpl<WmsMaterialGroupMapMapper, WmsMaterialGroupMap>
        implements IWmsMaterialGroupMapService {

    /** 字段长度上限，与 Flyway 建表脚本一致。 */
    private static final int MAX_GROUP_NUMBER_LENGTH = 64;
    private static final int MAX_GROUP_NAME_LENGTH = 100;
    private static final int MAX_REMARK_LENGTH = 200;

    @Autowired
    private WmsMaterialCategoryMapper categoryMapper;

    @Autowired
    private WmsMaterialMapper materialMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsMaterialGroupMapSaveResultVO saveBatchUpsert(List<WmsMaterialGroupMap> maps) {
        Map<String, WmsMaterialGroupMap> normalized = normalizeAndValidate(maps);

        Map<String, WmsMaterialGroupMap> existingByNumber = new LinkedHashMap<>();
        if (!normalized.isEmpty()) {
            // @TableLogic 自动追加 deleted_at = 0；已逻辑删除的同编码行不会命中，
            // 新插入的 deleted_at=0 行因此不会撞唯一索引
            List<WmsMaterialGroupMap> existingList = this.list(
                    new QueryWrapper<WmsMaterialGroupMap>()
                            .in("group_number", normalized.keySet()));
            for (WmsMaterialGroupMap item : existingList) {
                existingByNumber.put(item.getGroupNumber(), item);
            }
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (Map.Entry<String, WmsMaterialGroupMap> entry : normalized.entrySet()) {
            String groupNumber = entry.getKey();
            WmsMaterialGroupMap input = entry.getValue();
            WmsMaterialGroupMap existing = existingByNumber.get(groupNumber);
            if (existing == null) {
                this.save(newEntity(groupNumber, input));
                created++;
                continue;
            }
            if (applyChanges(existing, input)) {
                WmsUtils.fillUpdate(existing);
                this.updateById(existing);
                updated++;
            } else {
                unchanged++;
            }
        }

        WmsMaterialGroupMapSaveResultVO vo = new WmsMaterialGroupMapSaveResultVO();
        vo.setCreated(created);
        vo.setUpdated(updated);
        vo.setUnchanged(unchanged);
        vo.setMessage(String.format("保存分组映射 %d 条：新增 %d，更新 %d，无变化 %d",
                normalized.size(), created, updated, unchanged));
        return vo;
    }

    @Override
    public List<WmsMaterialGroupMap> listAll() {
        return this.list(new QueryWrapper<WmsMaterialGroupMap>()
                .orderByAsc("group_number"));
    }

    @Override
    public Map<String, String> typeByGroupNumber() {
        Map<String, String> mapping = new LinkedHashMap<>();
        List<WmsMaterialGroupMap> list = this.list(new QueryWrapper<WmsMaterialGroupMap>()
                .select("group_number", "category_type")
                .isNotNull("group_number")
                .ne("group_number", ""));
        for (WmsMaterialGroupMap item : list) {
            if (oConvertUtils.isNotEmpty(item.getGroupNumber())
                    && oConvertUtils.isNotEmpty(item.getCategoryType())) {
                mapping.put(item.getGroupNumber(), item.getCategoryType());
            }
        }
        return mapping;
    }

    @Override
    public List<WmsUnmappedGroupVO> listUnmappedGroups() {
        // 已同步成分类的分组：erp_group_number 非空（@TableLogic 只取未删除行）
        List<WmsMaterialCategory> candidates = categoryMapper.selectList(
                new QueryWrapper<WmsMaterialCategory>()
                        .isNotNull("erp_group_number")
                        .ne("erp_group_number", ""));
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        // 「未映射」的定义：category_type 为 NULL 或空串（同步时映射不到一律留 NULL）
        List<WmsMaterialCategory> unmapped = new ArrayList<>();
        List<String> categoryIds = new ArrayList<>();
        for (WmsMaterialCategory category : candidates) {
            if (oConvertUtils.isEmpty(category.getCategoryType())) {
                unmapped.add(category);
                categoryIds.add(category.getId());
            }
        }
        if (unmapped.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Long> materialCountByCategory = countMaterialsByCategory(categoryIds);
        List<WmsUnmappedGroupVO> result = new ArrayList<>(unmapped.size());
        for (WmsMaterialCategory category : unmapped) {
            WmsUnmappedGroupVO vo = new WmsUnmappedGroupVO();
            vo.setCategoryId(category.getId());
            vo.setGroupNumber(category.getErpGroupNumber());
            // 分组名称优先取同步时落的 erp_group_name，退回分类名称
            vo.setGroupName(oConvertUtils.isNotEmpty(category.getErpGroupName())
                    ? category.getErpGroupName() : category.getCategoryName());
            vo.setMaterialCount(materialCountByCategory.getOrDefault(category.getId(), 0L));
            result.add(vo);
        }
        // 物料多的分组优先认领：631 组覆盖 80% 物料，先标高频分组收益最大
        result.sort(Comparator.comparingLong(WmsUnmappedGroupVO::getMaterialCount).reversed()
                .thenComparing(WmsUnmappedGroupVO::getGroupNumber,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        return result;
    }

    /**
     * 全量校验 + 去重。重复分组编码按「后者覆盖前者」，与旧配置串的解析语义一致。
     *
     * @throws RuntimeException 任一字段非法；绝不部分落库
     */
    private Map<String, WmsMaterialGroupMap> normalizeAndValidate(List<WmsMaterialGroupMap> maps) {
        if (maps == null || maps.isEmpty()) {
            throw new RuntimeException("未提交任何分组映射");
        }
        Map<String, WmsMaterialGroupMap> normalized = new LinkedHashMap<>();
        for (int i = 0; i < maps.size(); i++) {
            WmsMaterialGroupMap input = maps.get(i);
            int rowNo = i + 1;
            if (input == null) {
                throw new RuntimeException("第 " + rowNo + " 行映射为空");
            }
            String groupNumber = trim(input.getGroupNumber());
            if (oConvertUtils.isEmpty(groupNumber)) {
                throw new RuntimeException("第 " + rowNo + " 行分组编码不能为空");
            }
            if (groupNumber.length() > MAX_GROUP_NUMBER_LENGTH) {
                throw new RuntimeException("第 " + rowNo + " 行分组编码超长（最多 "
                        + MAX_GROUP_NUMBER_LENGTH + " 字符）：" + groupNumber);
            }
            String categoryType = trim(input.getCategoryType());
            if (!WmsMaterialGroupMapping.ALLOWED_TYPES.contains(categoryType)) {
                throw new RuntimeException("第 " + rowNo + " 行分类类型非法（允许："
                        + String.join("/", WmsMaterialGroupMapping.ALLOWED_TYPES)
                        + "），实际：" + (categoryType == null ? "空" : categoryType));
            }
            String groupName = truncateChecked(trim(input.getGroupName()), MAX_GROUP_NAME_LENGTH, rowNo, "分组名称");
            String remark = truncateChecked(trim(input.getRemark()), MAX_REMARK_LENGTH, rowNo, "备注");

            WmsMaterialGroupMap item = new WmsMaterialGroupMap();
            item.setGroupNumber(groupNumber);
            item.setGroupName(groupName);
            item.setCategoryType(categoryType);
            item.setRemark(remark);
            normalized.put(groupNumber, item);
        }
        if (normalized.size() < maps.size()) {
            log.info("物料分组映射批量保存存在重复分组编码：提交 {} 条，去重后 {} 条",
                    maps.size(), normalized.size());
        }
        return normalized;
    }

    /** 新增：显式写 deleted_at=0，唯一索引才能与已逻辑删除的历史行共存。 */
    private WmsMaterialGroupMap newEntity(String groupNumber, WmsMaterialGroupMap input) {
        WmsMaterialGroupMap entity = new WmsMaterialGroupMap()
                .setGroupNumber(groupNumber)
                .setGroupName(input.getGroupName())
                .setCategoryType(input.getCategoryType())
                .setRemark(input.getRemark())
                .setDeletedAt(0L);
        WmsUtils.fillCreate(entity);
        return entity;
    }

    /**
     * 把入参差异写到已存在行上。
     *
     * <p>分组名称是同步回填的冗余字段：入参没给（null/空）就保留原值，避免前端批量
     * 认领时把名称清空；备注是业务字段，入参为空即清空（支持主动删除备注）。
     *
     * @return 是否有变化（无变化不更新，避免无意义的 update_time 抖动）
     */
    private boolean applyChanges(WmsMaterialGroupMap existing, WmsMaterialGroupMap input) {
        boolean changed = false;
        if (!input.getCategoryType().equals(existing.getCategoryType())) {
            existing.setCategoryType(input.getCategoryType());
            changed = true;
        }
        if (oConvertUtils.isNotEmpty(input.getGroupName())
                && !input.getGroupName().equals(existing.getGroupName())) {
            existing.setGroupName(input.getGroupName());
            changed = true;
        }
        String newRemark = oConvertUtils.isEmpty(input.getRemark()) ? null : input.getRemark();
        if (newRemark == null ? existing.getRemark() != null
                : !newRemark.equals(existing.getRemark())) {
            existing.setRemark(newRemark);
            changed = true;
        }
        return changed;
    }

    /**
     * 按分类统计未删除物料数。一次 group by 查完，避免 2039 个分组逐条 count 的 N+1。
     */
    private Map<String, Long> countMaterialsByCategory(List<String> categoryIds) {
        Map<String, Long> counts = new HashMap<>();
        QueryWrapper<WmsMaterial> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("category_id", "COUNT(1) AS material_count")
                .in("category_id", categoryIds)
                .groupBy("category_id");
        List<Map<String, Object>> rows = materialMapper.selectMaps(queryWrapper);
        if (rows == null) {
            return counts;
        }
        for (Map<String, Object> row : rows) {
            Object categoryId = row.get("category_id");
            Object count = row.get("material_count");
            if (categoryId != null && count instanceof Number) {
                counts.put(String.valueOf(categoryId), ((Number) count).longValue());
            }
        }
        return counts;
    }

    private static String truncateChecked(String value, int maxLength, int rowNo, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new RuntimeException("第 " + rowNo + " 行" + field + "超长（最多 "
                    + maxLength + " 字符）");
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
