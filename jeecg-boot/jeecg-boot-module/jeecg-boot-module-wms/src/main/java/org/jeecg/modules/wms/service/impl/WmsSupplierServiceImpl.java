package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsSupplierServiceImpl extends ServiceImpl<WmsSupplierMapper, WmsSupplier> implements IWmsSupplierService {

    @Override
    public void addSupplier(WmsSupplier supplier) {
        trimFields(supplier);
        if (oConvertUtils.isEmpty(supplier.getSupplierCode())) {
            throw new RuntimeException("供应商编码不能为空");
        }
        if (oConvertUtils.isEmpty(supplier.getSupplierName())) {
            throw new RuntimeException("供应商名称不能为空");
        }
        if (checkCodeExists(supplier.getSupplierCode(), null)) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(supplier.getStatus())) {
            supplier.setStatus(WmsConstants.STATUS_ENABLE);
        }
        supplier.setId(null);
        supplier.setDelFlag(0);
        WmsUtils.fillCreate(supplier);
        this.save(supplier);
    }

    @Override
    public void editSupplier(WmsSupplier supplier) {
        trimFields(supplier);
        if (oConvertUtils.isEmpty(supplier.getSupplierCode())) {
            throw new RuntimeException("供应商编码不能为空");
        }
        if (oConvertUtils.isEmpty(supplier.getSupplierName())) {
            throw new RuntimeException("供应商名称不能为空");
        }
        if (checkCodeExists(supplier.getSupplierCode(), supplier.getId())) {
            throw new RuntimeException("编码已存在，请重新输入");
        }
        WmsUtils.fillUpdate(supplier);
        this.updateById(supplier);
    }

    @Override
    public void disableSupplier(String id) {
        WmsSupplier supplier = new WmsSupplier();
        supplier.setId(id);
        supplier.setStatus(WmsConstants.STATUS_DISABLE);
        WmsUtils.fillUpdate(supplier);
        this.updateById(supplier);
    }

    @Override
    public void removeSupplier(String id) {
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
        QueryWrapper<WmsSupplier> queryWrapper = new QueryWrapper<>();
        // 代码逻辑说明: 按 trim 后的编码比对，与 addSupplier/editSupplier 的入库值保持一致，
        // 否则 " SUP001" 会绕过 "SUP001" 的查重，最终撞上唯一索引报 500
        queryWrapper.eq("supplier_code", code.trim());
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public List<WmsSupplier> listAllEnabled() {
        QueryWrapper<WmsSupplier> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", WmsConstants.STATUS_ENABLE);
        queryWrapper.orderByAsc("supplier_code");
        return this.list(queryWrapper);
    }

    /**
     * 去掉编码/名称/联系人等文本字段的首尾空格，避免「看起来一样、实际不同」的脏数据。
     */
    private void trimFields(WmsSupplier supplier) {
        supplier.setSupplierCode(trim(supplier.getSupplierCode()));
        supplier.setSupplierName(trim(supplier.getSupplierName()));
        supplier.setContact(trim(supplier.getContact()));
        supplier.setPhone(trim(supplier.getPhone()));
        supplier.setSupplyScope(trim(supplier.getSupplyScope()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
