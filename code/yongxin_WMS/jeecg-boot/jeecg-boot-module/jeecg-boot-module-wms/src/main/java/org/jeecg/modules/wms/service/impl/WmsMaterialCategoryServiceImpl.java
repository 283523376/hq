package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 物料分类
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Slf4j
@Service
public class WmsMaterialCategoryServiceImpl extends ServiceImpl<WmsMaterialCategoryMapper, WmsMaterialCategory>
        implements IWmsMaterialCategoryService {

    @Autowired
    private WmsMaterialMapper wmsMaterialMapper;

    @Override
    public List<SelectTreeModel> queryTreeList() {
        List<WmsMaterialCategory> all = this.list(new QueryWrapper<WmsMaterialCategory>().orderByAsc("category_code"));
        Map<String, List<WmsMaterialCategory>> byParent = new HashMap<>();
        for (WmsMaterialCategory c : all) {
            String pid = oConvertUtils.isEmpty(c.getParentId()) ? WmsConstants.ROOT_PID_VALUE : c.getParentId();
            byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(c);
        }
        return buildChildren(WmsConstants.ROOT_PID_VALUE, byParent);
    }

    /**
     * 递归把「按父ID分组」的映射转成 SelectTreeModel 树。
     */
    private List<SelectTreeModel> buildChildren(String parentId, Map<String, List<WmsMaterialCategory>> byParent) {
        List<SelectTreeModel> result = new ArrayList<>();
        List<WmsMaterialCategory> children = byParent.get(parentId);
        if (children == null) {
            return result;
        }
        for (WmsMaterialCategory c : children) {
            List<SelectTreeModel> sub = buildChildren(c.getId(), byParent);
            SelectTreeModel model = new SelectTreeModel();
            model.setKey(c.getId());
            model.setValue(c.getId());
            model.setTitle(c.getCategoryName());
            model.setParentId(parentId);
            model.setChildren(sub);
            model.setLeaf(sub.isEmpty());
            result.add(model);
        }
        return result;
    }

    @Override
    public void addCategory(WmsMaterialCategory category) {
        if (checkCodeExists(category.getCategoryCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (checkNameExists(category.getCategoryName(), category.getCategoryType(), null)) {
            throw new RuntimeException(duplicateNameMessage(category.getCategoryName()));
        }
        if (oConvertUtils.isEmpty(category.getParentId())) {
            category.setParentId(WmsConstants.ROOT_PID_VALUE);
        }
        if (oConvertUtils.isEmpty(category.getStatus())) {
            category.setStatus(WmsConstants.STATUS_ENABLE);
        }
        if (oConvertUtils.isEmpty(category.getHasChild())) {
            category.setHasChild(WmsConstants.NO);
        }
        WmsUtils.fillCreate(category);
        this.save(category);
        markParentHasChild(category.getParentId());
    }

    @Override
    public void editCategory(WmsMaterialCategory category) {
        if (checkCodeExists(category.getCategoryCode(), category.getId())) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        // 只在「名称/类型被改动」时查重：同步侧回写 erp_group_number 也走本方法，
        // 名称没变就不该被历史重复数据挡住（未跑合并脚本的库仍可能有同名分类）。
        if (nameOrTypeChanged(category)
                && checkNameExists(category.getCategoryName(), category.getCategoryType(), category.getId())) {
            throw new RuntimeException(duplicateNameMessage(category.getCategoryName()));
        }
        WmsUtils.fillUpdate(category);
        this.updateById(category);
    }

    /** 名称或类型是否与库内当前值不同；查不到当前行时按「已改动」处理（保守）。 */
    private boolean nameOrTypeChanged(WmsMaterialCategory category) {
        if (oConvertUtils.isEmpty(category.getId())) {
            return true;
        }
        WmsMaterialCategory current = this.getById(category.getId());
        if (current == null) {
            return true;
        }
        return !nullToEmpty(current.getCategoryName()).equals(nullToEmpty(category.getCategoryName()))
                || !nullToEmpty(current.getCategoryType()).equals(nullToEmpty(category.getCategoryType()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String duplicateNameMessage(String categoryName) {
        return "已存在同名同类型的分类「" + nullToEmpty(categoryName) + "」，请直接使用该分类或调整名称";
    }

    @Override
    public void disableCategory(String id) {
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId(id);
        category.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(category);
        this.updateById(category);
    }

    @Override
    public WmsDeleteCheckVO checkDelete(String id) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (oConvertUtils.isEmpty(id)) {
            return vo;
        }
        long materialCount = wmsMaterialMapper.selectCount(
                new QueryWrapper<WmsMaterial>().eq("category_id", id));
        if (materialCount > 0) {
            vo.addBlocker("该分类下存在物料", materialCount, Collections.emptyList());
        }
        long childCount = this.count(new QueryWrapper<WmsMaterialCategory>().eq("parent_id", id));
        if (childCount > 0) {
            vo.addBlocker("该分类下存在子分类", childCount, Collections.emptyList());
        }
        return vo;
    }

    @Override
    public void removeCategory(String id) {
        WmsDeleteCheckVO check = checkDelete(id);
        if (!check.isDeletable()) {
            // blocker 顺序即文案优先级：物料先于子分类，与既有用例（BD001-T07）一致
            throw new RuntimeException(check.getBlockers().get(0).getMessage() + "，不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsMaterialCategory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("category_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public boolean checkNameExists(String categoryName, String categoryType, String id) {
        if (oConvertUtils.isEmpty(categoryName)) {
            return false;
        }
        QueryWrapper<WmsMaterialCategory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("category_name", categoryName);
        if (oConvertUtils.isEmpty(categoryType)) {
            queryWrapper.and(w -> w.isNull("category_type").or().eq("category_type", ""));
        } else {
            queryWrapper.eq("category_type", categoryType);
        }
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public String queryIdByCode(String categoryCode) {
        if (oConvertUtils.isEmpty(categoryCode)) {
            return null;
        }
        WmsMaterialCategory category = this.getOne(
                new QueryWrapper<WmsMaterialCategory>().eq("category_code", categoryCode).last("limit 1"), false);
        return category == null ? null : category.getId();
    }

    /**
     * 回写父节点的 has_child。
     */
    private void markParentHasChild(String parentId) {
        if (oConvertUtils.isEmpty(parentId) || WmsConstants.ROOT_PID_VALUE.equals(parentId)) {
            return;
        }
        WmsMaterialCategory parent = this.getById(parentId);
        if (parent != null && !WmsConstants.YES.equals(parent.getHasChild())) {
            parent.setHasChild(WmsConstants.YES);
            WmsUtils.fillUpdate(parent);
            this.updateById(parent);
        }
    }
}
