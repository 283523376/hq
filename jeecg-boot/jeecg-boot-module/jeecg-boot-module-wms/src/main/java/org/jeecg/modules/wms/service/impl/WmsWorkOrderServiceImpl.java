package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * @Description: 生产工单服务：扫码查询、MES 推送/回源、手工维护与删除守卫。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsWorkOrderServiceImpl extends ServiceImpl<WmsWorkOrderMapper, WmsWorkOrder>
        implements IWmsWorkOrderService {

    @Autowired
    private WmsMaterialMapper materialMapper;

    @Autowired
    private WmsProductionInboundMapper inboundMapper;

    @Override
    public WmsWorkOrder getByWorkOrderNo(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            return null;
        }
        return baseMapper.selectOne(Wrappers.<WmsWorkOrder>query()
                .eq("work_order_no", workOrderNo.trim()).last("LIMIT 1"));
    }

    @Override
    public WmsWorkOrderInfoVO scan(String workOrderNo) {
        WmsWorkOrder order = getByWorkOrderNo(workOrderNo);
        if (order == null) {
            // 本地无单直接拒绝；ERP 回源统一走 scanWithErpFallback（IWmsKingdeeWorkOrderSyncService）
            throw new RuntimeException("工单号无效");
        }
        BigDecimal inbounded = inboundMapper.sumInboundedQty(order.getWorkOrderNo());
        if (inbounded == null) {
            inbounded = BigDecimal.ZERO;
        }
        BigDecimal plan = order.getPlanQty() == null ? BigDecimal.ZERO : order.getPlanQty();
        WmsWorkOrderInfoVO vo = new WmsWorkOrderInfoVO();
        vo.setWorkOrderNo(order.getWorkOrderNo());
        vo.setMaterialId(order.getMaterialId());
        vo.setMaterialCode(order.getMaterialCode());
        vo.setMaterialName(order.getMaterialName());
        vo.setProductType(order.getProductType());
        vo.setPlanQty(plan);
        vo.setBatchNo(order.getBatchNo());
        vo.setWorkOrderStatus(order.getWorkOrderStatus());
        vo.setInboundedQty(inbounded);
        vo.setRemainingQty(plan.subtract(inbounded));
        vo.setIdentType(WmsConstants.PRODUCT_TYPE_FINISHED.equals(order.getProductType())
                ? WmsConstants.IDENT_TYPE_QRCODE : WmsConstants.IDENT_TYPE_RFID);
        return vo;
    }

    /**
     * 工单状态秩（BD-002 V2.2）：同步/推送合并时取 rank-max，状态只进不退；
     * 已关闭为终态（rank 最大，任何来源都不能把它拖回去）。
     */
    static int statusRank(String status) {
        if (WmsConstants.WORK_ORDER_STATUS_PENDING.equals(status)) {
            return 0;
        }
        if (WmsConstants.WORK_ORDER_STATUS_RELEASED.equals(status)) {
            return 1;
        }
        if (WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS.equals(status)) {
            return 2;
        }
        if (WmsConstants.WORK_ORDER_STATUS_FINISHED.equals(status)) {
            return 3;
        }
        if (WmsConstants.WORK_ORDER_STATUS_CLOSED.equals(status)) {
            return 4;
        }
        return -1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsWorkOrder push(WmsWorkOrderPushDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        if (oConvertUtils.isEmpty(dto.getMaterialCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", dto.getMaterialCode().trim()).last("LIMIT 1"));
        if (material == null) {
            throw new RuntimeException("物料编码不存在");
        }
        WmsWorkOrder order = getByWorkOrderNo(dto.getWorkOrderNo());
        boolean create = order == null;
        String incomingStatus = oConvertUtils.isEmpty(dto.getWorkOrderStatus())
                ? WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS : dto.getWorkOrderStatus();
        String source;
        String status;
        if (create) {
            // 新建：来源显式写（ERP/MES），空值按 MES 兼容旧调用方
            source = oConvertUtils.isEmpty(dto.getSource())
                    ? WmsConstants.WORK_ORDER_SOURCE_MES : dto.getSource();
            status = incomingStatus;
            order = new WmsWorkOrder().setSource(source);
        } else {
            // 更新：来源不改；状态 rank-max 只进不退；已关闭为终态
            source = order.getSource();
            status = statusRank(incomingStatus) > statusRank(order.getWorkOrderStatus())
                    ? incomingStatus : order.getWorkOrderStatus();
        }
        order.setWorkOrderNo(dto.getWorkOrderNo().trim())
                .setMaterialId(material.getId())
                .setMaterialCode(material.getMaterialCode())
                .setMaterialName(material.getMaterialName())
                .setProductType(dto.getProductType())
                .setPlanQty(dto.getPlanQty())
                .setBatchNo(dto.getBatchNo())
                .setWorkOrderStatus(status)
                .setMesOrderNo(dto.getMesOrderNo())
                .setRemark(dto.getRemark());
        // ERP 原始状态只读留痕：仅同步方显式携带时刷新，MES 推送不得清空
        if (oConvertUtils.isNotEmpty(dto.getErpDocumentStatus())) {
            order.setErpDocumentStatus(dto.getErpDocumentStatus());
        }
        // 同步状态（BD-002 V2.2）：ERP 同步成功即刷成功/时间/清零重试；MES 推送不动同步字段
        boolean erpSync = WmsConstants.WORK_ORDER_SOURCE_ERP.equals(source)
                || oConvertUtils.isNotEmpty(dto.getErpDocumentStatus());
        if (erpSync) {
            order.setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS)
                    .setLastSyncTime(new java.util.Date())
                    .setRetryCount(0)
                    .setLastSyncError(null);
        } else if (create) {
            order.setSyncStatus(WmsConstants.SYNC_STATUS_NOT_REQUIRED);
        }
        validate(order);
        if (create) {
            order.setId(null);
            order.setDeletedAt(0L);
            WmsUtils.fillCreate(order);
            save(order);
        } else {
            WmsUtils.fillUpdate(order);
            updateById(order);
        }
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSyncFailed(String workOrderNo, String errorMessage) {
        WmsWorkOrder order = getByWorkOrderNo(workOrderNo);
        if (order == null) {
            return;
        }
        int retry = order.getRetryCount() == null ? 0 : order.getRetryCount();
        int fail = order.getSyncFailCount() == null ? 0 : order.getSyncFailCount();
        order.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED)
                .setRetryCount(retry + 1)
                .setSyncFailCount(fail + 1)
                .setLastSyncError(errorMessage);
        WmsUtils.fillUpdate(order);
        updateById(order);
    }

    @Override
    public IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo,
                                        String productType, String workOrderStatus,
                                        String source, String syncStatus,
                                        String startDate, String endDate) {
        QueryWrapper<WmsWorkOrder> qw = new QueryWrapper<>();
        if (oConvertUtils.isNotEmpty(workOrderNo)) {
            qw.like("work_order_no", workOrderNo.trim());
        }
        if (oConvertUtils.isNotEmpty(productType)) {
            qw.eq("product_type", productType);
        }
        if (oConvertUtils.isNotEmpty(workOrderStatus)) {
            qw.eq("work_order_status", workOrderStatus);
        }
        if (oConvertUtils.isNotEmpty(source)) {
            qw.eq("source", source);
        }
        if (oConvertUtils.isNotEmpty(syncStatus)) {
            qw.eq("sync_status", syncStatus);
        }
        if (oConvertUtils.isNotEmpty(startDate)) {
            qw.ge("create_time", startDate + " 00:00:00");
        }
        if (oConvertUtils.isNotEmpty(endDate)) {
            qw.le("create_time", endDate + " 23:59:59");
        }
        qw.orderByDesc("create_time");
        return page(new Page<>(pageNo, pageSize), qw);
    }

    private void validate(WmsWorkOrder order) {
        if (oConvertUtils.isEmpty(order.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        if (oConvertUtils.isEmpty(order.getMaterialId())) {
            throw new RuntimeException("物料编码不能为空");
        }
        if (!WmsConstants.PRODUCT_TYPE_SEMI.equals(order.getProductType())
                && !WmsConstants.PRODUCT_TYPE_FINISHED.equals(order.getProductType())) {
            throw new RuntimeException("产品类型必须是半成品或成品");
        }
        if (order.getPlanQty() == null || order.getPlanQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("计划数量必须大于 0");
        }
        if (oConvertUtils.isEmpty(order.getBatchNo())) {
            throw new RuntimeException("批次号不能为空");
        }
    }
}
