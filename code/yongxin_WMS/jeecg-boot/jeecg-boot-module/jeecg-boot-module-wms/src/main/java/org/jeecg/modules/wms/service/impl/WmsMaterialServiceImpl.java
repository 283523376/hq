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
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInboundDetailMapper inboundDetailMapper;

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
    public WmsDeleteCheckVO checkDelete(String id) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (oConvertUtils.isEmpty(id)) {
            return vo;
        }
        // RK-001：库存表接入后，有库存记录的物料不可删（否则留下孤儿库存）
        // mapper 为 null 只会出现在单测裸构造场景（生产缺 bean 时 Spring 启动即失败）
        long inventoryCount = inventoryMapper == null ? 0L : inventoryMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<org.jeecg.modules.wms.entity.WmsInventory>()
                        .eq("material_id", id));
        if (inventoryCount > 0) {
            vo.addBlocker("该物料已存在库存记录", inventoryCount, java.util.Collections.singletonList(id));
        }
        // 入库流程进行中的明细同样引用该物料：此时删物料会让上架卡死（物料不存在）
        long activeInbound = inboundDetailMapper == null ? 0L : inboundDetailMapper.countActiveByMaterial(id);
        if (activeInbound > 0) {
            vo.addBlocker("该物料存在进行中的入库单引用", activeInbound, java.util.Collections.singletonList(id));
        }
        return vo;
    }

    @Override
    public WmsBatchDeleteCheckVO checkDeleteBatch(List<String> ids) {
        WmsBatchDeleteCheckVO vo = new WmsBatchDeleteCheckVO();
        if (ids == null || ids.isEmpty()) {
            return vo;
        }
        for (String id : ids) {
            WmsDeleteCheckVO check = checkDelete(id);
            if (!check.isDeletable()) {
                // 与库位批量预检同契约：逐行报出不可删原因，前端据此列明细
                WmsMaterial material = this.getById(id);
                vo.addBlocked(id,
                        material == null ? id : material.getMaterialCode(),
                        check.getBlockers().stream()
                                .map(WmsDeleteCheckVO.Blocker::getMessage)
                                .collect(java.util.stream.Collectors.joining("；")));
            }
        }
        return vo;
    }

    @Override
    public void removeMaterial(String id) {
        WmsDeleteCheckVO check = checkDelete(id);
        if (!check.isDeletable()) {
            throw new RuntimeException(check.getBlockers().get(0).getMessage() + "，不允许删除");
        }
        this.removeById(id);
    }

    /** 批量删除整体在一个事务里（同 WmsSupplierServiceImpl.removeBatch 的理由）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                removeMaterial(id);
            }
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
