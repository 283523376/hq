package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 物料主数据
 * @Author: 永鑫WMS
 * @Date: 2026-10-13
 */
@Slf4j
@Service
public class WmsMaterialServiceImpl extends ServiceImpl<WmsMaterialMapper, WmsMaterial> implements IWmsMaterialService {

    @Autowired
    private WmsMaterialCategoryMapper wmsMaterialCategoryMapper;

    @Override
    public void addMaterial(WmsMaterial material) {
        if (checkCodeExists(material.getMaterialCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(material.getStatus())) {
            material.setStatus(WmsConstants.STATUS_ENABLE);
        }
        WmsUtils.fillCreate(material);
        this.save(material);
    }

    @Override
    public void editMaterial(WmsMaterial material) {
        if (checkCodeExists(material.getMaterialCode(), material.getId())) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(material);
        this.updateById(material);
    }

    @Override
    public void disableMaterial(String id) {
        WmsMaterial material = new WmsMaterial();
        material.setId(id);
        material.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(material);
        this.updateById(material);
    }

    @Override
    public void removeMaterial(String id) {
        this.removeById(id);
    }

    @Override
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            this.removeByIds(ids);
        }
    }

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsMaterial> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("material_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void fillCategoryInfo(List<WmsMaterial> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> categoryIds = new HashSet<>();
        for (WmsMaterial material : list) {
            if (oConvertUtils.isNotEmpty(material.getCategoryId())) {
                categoryIds.add(material.getCategoryId());
            }
        }
        if (categoryIds.isEmpty()) {
            return;
        }
        List<WmsMaterialCategory> categories = wmsMaterialCategoryMapper.selectBatchIds(categoryIds);
        Map<String, WmsMaterialCategory> categoryMap = new HashMap<>();
        for (WmsMaterialCategory category : categories) {
            categoryMap.put(category.getId(), category);
        }
        for (WmsMaterial material : list) {
            WmsMaterialCategory category = material.getCategoryId() == null ? null : categoryMap.get(material.getCategoryId());
            if (category != null) {
                material.setCategoryName(category.getCategoryName());
                material.setCategoryCode(category.getCategoryCode());
            }
        }
    }

    @Override
    public String resolveCategoryIdByCode(String categoryCode) {
        if (oConvertUtils.isEmpty(categoryCode)) {
            return null;
        }
        WmsMaterialCategory category = wmsMaterialCategoryMapper.selectOne(
                new QueryWrapper<WmsMaterialCategory>().eq("category_code", categoryCode).last("limit 1"));
        return category == null ? null : category.getId();
    }
}
