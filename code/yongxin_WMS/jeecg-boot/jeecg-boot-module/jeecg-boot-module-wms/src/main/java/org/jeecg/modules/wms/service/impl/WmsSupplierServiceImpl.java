package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 供应商
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsSupplierServiceImpl extends ServiceImpl<WmsSupplierMapper, WmsSupplier> implements IWmsSupplierService {

    @Autowired
    private WmsOrderMapper wmsOrderMapper;

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
        // 显式标记「未删除」：删除时间戳方案下 0 = 未删除（见 WmsSupplier.deletedAt）
        supplier.setDeletedAt(0L);
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
    public WmsDeleteCheckVO checkDelete(String id) {
        WmsDeleteCheckVO vo = new WmsDeleteCheckVO();
        if (oConvertUtils.isEmpty(id)) {
            return vo;
        }
        long orderCount = wmsOrderMapper.selectCount(
                new QueryWrapper<WmsOrder>().eq("supplier_id", id));
        if (orderCount > 0) {
            // 样本取最近 5 单的订单号，供前端禁止弹窗展示「被哪些单占用」
            List<WmsOrder> recent = wmsOrderMapper.selectList(new QueryWrapper<WmsOrder>()
                    .eq("supplier_id", id).orderByDesc("create_time").last("limit 5"));
            vo.addBlocker("存在关联订单", orderCount,
                    recent.stream().map(WmsOrder::getOrderNo).collect(Collectors.toList()));
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
                WmsSupplier supplier = this.getById(id);
                vo.addBlocked(id,
                        supplier == null ? id : supplier.getSupplierCode(),
                        check.getBlockers().stream()
                                .map(WmsDeleteCheckVO.Blocker::getMessage)
                                .collect(Collectors.joining("；")));
            }
        }
        return vo;
    }

    @Override
    public void removeSupplier(String id) {
        WmsDeleteCheckVO check = checkDelete(id);
        if (!check.isDeletable()) {
            // 文案逐字对齐 TC-BD002-006 预期结果，前端与测试都按原文匹配
            throw new RuntimeException("该供应商存在关联订单，不允许删除");
        }
        this.removeById(id);
    }

    /**
     * 批量删除必须整体在一个事务里：循环复用单条守卫，第 N 条被拒时前 N-1 条一并回滚——
     * 否则「部分成功」会让接口报错但数据已删一半（错误的状态结论，与设备 bind 同一个教训）。
     * 自调用说明：事务注解在本方法上、由 controller 经代理进入，内部 this.removeSupplier 不经过代理无碍。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeBatch(List<String> ids) {
        if (ids != null && !ids.isEmpty()) {
            for (String id : ids) {
                removeSupplier(id);
            }
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
