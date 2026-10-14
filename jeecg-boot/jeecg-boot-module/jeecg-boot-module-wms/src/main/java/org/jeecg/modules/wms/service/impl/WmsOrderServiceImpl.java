package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 订单
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsOrderServiceImpl extends ServiceImpl<WmsOrderMapper, WmsOrder> implements IWmsOrderService {

    /** 允许的订单类型。 */
    private static final Set<String> ALLOWED_ORDER_TYPES = new HashSet<>(Arrays.asList(
            WmsConstants.ORDER_TYPE_COMPANY,
            WmsConstants.ORDER_TYPE_PURCHASE,
            WmsConstants.ORDER_TYPE_OUTSOURCE));

    @Autowired
    private WmsSupplierMapper wmsSupplierMapper;

    /** 仅供单测注入供应商 Mapper（裸 new 时该字段为空，回填会 NPE）。 */
    void setSupplierMapperForTest(WmsSupplierMapper wmsSupplierMapper) {
        this.wmsSupplierMapper = wmsSupplierMapper;
    }

    // ------------------------------------------------------------ 创建

    @Override
    public void addOrder(WmsOrder order) {
        order.setOrderNo(trim(order.getOrderNo()));
        if (oConvertUtils.isEmpty(order.getOrderNo())) {
            throw new RuntimeException("订单号不能为空");
        }
        if (!ALLOWED_ORDER_TYPES.contains(order.getOrderType())) {
            throw new RuntimeException("订单类型不合法");
        }
        if (checkOrderNoExists(order.getOrderNo(), null)) {
            throw new RuntimeException("订单号已存在，请重新输入");
        }
        order.setId(null);
        order.setDelFlag(0);
        order.setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setSyncStatus(WmsConstants.SYNC_STATUS_PENDING);
        order.setRetryCount(0);
        order.setAuditBy(null);
        order.setAuditTime(null);
        order.setAuditRemark(null);
        order.setDispatchBy(null);
        order.setDispatchTime(null);
        order.setLastSyncError(null);
        order.setLastSyncTime(null);
        WmsUtils.fillCreate(order);
        this.save(order);
    }

    // ------------------------------------------------------------ 编辑

    @Override
    public void editOrder(WmsOrder order) {
        WmsOrder existing = this.getById(order.getId());
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())
                && !WmsConstants.ORDER_STATUS_REJECTED.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核或已驳回状态的订单可编辑");
        }
        order.setOrderNo(trim(order.getOrderNo()));
        if (oConvertUtils.isEmpty(order.getOrderNo())) {
            throw new RuntimeException("订单号不能为空");
        }
        if (!ALLOWED_ORDER_TYPES.contains(order.getOrderType())) {
            throw new RuntimeException("订单类型不合法");
        }
        if (checkOrderNoExists(order.getOrderNo(), order.getId())) {
            throw new RuntimeException("订单号已存在，请重新输入");
        }
        // 代码逻辑说明: 已驳回的订单被重新编辑即视为重新提交，回到「待审核」，
        // 并清空上一轮审核痕迹；旧意见的历史由操作日志（@AutoLog）承担
        order.setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setAuditBy(null);
        order.setAuditTime(null);
        order.setAuditRemark(null);
        WmsUtils.fillUpdate(order);
        this.updateById(order);
    }

    // ------------------------------------------------------------ 审核

    @Override
    public void auditOrder(WmsOrderAuditDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getId())) {
            throw new RuntimeException("订单ID不能为空");
        }
        if (dto.getPass() == null) {
            throw new RuntimeException("请选择审核结果");
        }
        WmsOrder existing = this.getById(dto.getId());
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核状态的订单可审核");
        }
        if (!dto.getPass() && oConvertUtils.isEmpty(dto.getAuditRemark())) {
            throw new RuntimeException("驳回时必须填写驳回原因");
        }
        WmsOrder update = new WmsOrder();
        update.setId(dto.getId());
        update.setOrderStatus(Boolean.TRUE.equals(dto.getPass())
                ? WmsConstants.ORDER_STATUS_AUDITED
                : WmsConstants.ORDER_STATUS_REJECTED);
        update.setAuditBy(WmsUtils.currentUsername());
        update.setAuditTime(new Date());
        update.setAuditRemark(trim(dto.getAuditRemark()));
        WmsUtils.fillUpdate(update);
        this.updateById(update);
        // 让调用方持有的实体也反映最终状态（单测据此断言）
        existing.setOrderStatus(update.getOrderStatus());
        existing.setAuditRemark(update.getAuditRemark());
    }

    // ------------------------------------------------------------ 删除

    @Override
    public void removeOrder(String id) {
        WmsOrder existing = this.getById(id);
        if (existing == null) {
            throw new RuntimeException("未找到对应订单");
        }
        if (!WmsConstants.ORDER_STATUS_PENDING_AUDIT.equals(existing.getOrderStatus())
                && !WmsConstants.ORDER_STATUS_REJECTED.equals(existing.getOrderStatus())) {
            throw new RuntimeException("仅待审核或已驳回状态的订单可删除");
        }
        this.removeById(id);
    }

    @Override
    public void removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            removeOrder(id);
        }
    }

    // ------------------------------------------------------------ 查重与回填

    @Override
    public boolean checkOrderNoExists(String orderNo, String id) {
        if (oConvertUtils.isEmpty(orderNo)) {
            return false;
        }
        QueryWrapper<WmsOrder> queryWrapper = new QueryWrapper<>();
        // 代码逻辑说明: 与入库值一样按 trim 后比对，防止 " PO001" 绕过 "PO001" 的查重
        queryWrapper.eq("order_no", orderNo.trim());
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void fillSupplierName(List<WmsOrder> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<String> supplierIds = new HashSet<>();
        for (WmsOrder order : list) {
            if (oConvertUtils.isNotEmpty(order.getSupplierId())) {
                supplierIds.add(order.getSupplierId());
            }
        }
        if (supplierIds.isEmpty()) {
            return;
        }
        // 代码逻辑说明: selectBatchIds 会自动带上 del_flag=0，已删除的供应商查不到；
        // 此处刻意不报错，回填不到就留空，避免历史订单列表整体 500
        List<WmsSupplier> suppliers = wmsSupplierMapper.selectBatchIds(supplierIds);
        Map<String, WmsSupplier> supplierMap = new HashMap<>(suppliers.size());
        for (WmsSupplier supplier : suppliers) {
            supplierMap.put(supplier.getId(), supplier);
        }
        for (WmsOrder order : list) {
            WmsSupplier supplier = order.getSupplierId() == null ? null : supplierMap.get(order.getSupplierId());
            if (supplier != null) {
                order.setSupplierName(supplier.getSupplierName());
            }
        }
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
