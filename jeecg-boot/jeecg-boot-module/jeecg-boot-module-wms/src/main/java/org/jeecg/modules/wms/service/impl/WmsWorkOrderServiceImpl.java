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
import java.util.Objects;

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
        // 只有显式的「半成品」才用 RFID；工单没带类型时按成品（完工入库）走二维码
        vo.setIdentType(WmsConstants.PRODUCT_TYPE_SEMI.equals(order.getProductType())
                ? WmsConstants.IDENT_TYPE_RFID : WmsConstants.IDENT_TYPE_QRCODE);
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
        if (WmsConstants.WORK_ORDER_STATUS_CLOSED.equals(status)
                || WmsConstants.WORK_ORDER_STATUS_CANCELLED.equals(status)) {
            // 两个终态并列最高：任何来源都不能把终态拖回「已下达」。
            // 但两者的产生渠道不同（WMS 关单 / ERP 作废同步），见 pushInternal 的守卫。
            return 4;
        }
        return -1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsWorkOrder push(WmsWorkOrderPushDTO dto) {
        return pushInternal(dto, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markCancelledByErp(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            return false;
        }
        WmsWorkOrder order = getByWorkOrderNo(workOrderNo);
        if (order == null) {
            // 从没同步进来过：没有可纠正的对象
            return false;
        }
        if (WmsConstants.WORK_ORDER_STATUS_CANCELLED.equals(order.getWorkOrderStatus())
                || WmsConstants.WORK_ORDER_STATUS_CLOSED.equals(order.getWorkOrderStatus())) {
            // 已作废：幂等跳过；已关闭：WMS 关单的终态，不被作废覆盖（两个终态互不侵犯）
            return false;
        }
        order.setWorkOrderStatus(WmsConstants.WORK_ORDER_STATUS_CANCELLED);
        order.setUpdateTime(new java.util.Date());
        baseMapper.updateById(order);
        return true;
    }

    private WmsWorkOrder pushInternal(WmsWorkOrderPushDTO dto, boolean retryOnDuplicate) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        String incomingStatus = oConvertUtils.isEmpty(dto.getWorkOrderStatus())
                ? WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS : dto.getWorkOrderStatus();
        if (WmsConstants.WORK_ORDER_STATUS_CLOSED.equals(incomingStatus)) {
            // 已关闭只能由 WMS 关单动作产生；外部 MES/ERP 推送不得直接关单（评审 I6）
            throw new RuntimeException("工单状态已关闭只能由WMS关单产生");
        }
        if (WmsConstants.WORK_ORDER_STATUS_CANCELLED.equals(incomingStatus)
                && !WmsConstants.WORK_ORDER_SOURCE_ERP.equals(dto.getSource())) {
            // 已作废同样不能被随手写进来：它代表「ERP 把这单废了」，只有同步通道能这么说
            throw new RuntimeException("工单状态「已作废」只能由 ERP 作废同步产生");
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
        if (!create) {
            assertProductionFactsUntouched(order, material, dto.getPlanQty(), dto.getProductType());
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
        // 工单类型同理：只有携带值时才覆盖，MES 推送（永远不带 orderType）不得把它清空
        if (oConvertUtils.isNotEmpty(dto.getOrderType())) {
            order.setOrderType(dto.getOrderType());
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
            try {
                save(order);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发同步同一新单：输掉插入竞争的一方改为更新，避免把成功单误标失败（评审 I7）
                if (!retryOnDuplicate) {
                    throw e;
                }
                return pushInternal(dto, false);
            }
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
                .setLastSyncError(truncateError(errorMessage));
        WmsUtils.fillUpdate(order);
        updateById(order);
    }

    @Override
    public IPage<WmsWorkOrder> listPage(int pageNo, int pageSize, String workOrderNo,
                                        String productType, String workOrderStatus,
                                        String source, String syncStatus,
                                        String startDate, String endDate, String keyword) {
        QueryWrapper<WmsWorkOrder> qw = new QueryWrapper<>();
        // 全局关键词（2026-11-02）：跨工单号/生产订单号模糊，OR 包在 and 里避免优先级错误
        if (oConvertUtils.isNotEmpty(keyword)) {
            String kw = keyword.trim();
            qw.and(w -> w.like("work_order_no", kw).or().like("mes_order_no", kw));
        }
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

    private String truncateError(String error) {
        if (error == null || error.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    private void validate(WmsWorkOrder order) {
        if (oConvertUtils.isEmpty(order.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        if (oConvertUtils.isEmpty(order.getMaterialId())) {
            throw new RuntimeException("物料编码不能为空");
        }
        // 产品类型允许为空：金蝶没有成品/半成品之分，工单只是生产任务，
        // 真正的类型由「入库来源」决定（工序汇报=半成品、完工入库=成品，2026-10-28 用户口径）
        if (oConvertUtils.isNotEmpty(order.getProductType())
                && !WmsConstants.PRODUCT_TYPE_SEMI.equals(order.getProductType())
                && !WmsConstants.PRODUCT_TYPE_FINISHED.equals(order.getProductType())) {
            throw new RuntimeException("产品类型只能为空、半成品或成品");
        }
        if (order.getPlanQty() == null || order.getPlanQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("计划数量必须大于 0");
        }
        if (oConvertUtils.isEmpty(order.getBatchNo())) {
            throw new RuntimeException("批次号不能为空");
        }
        // 列宽/精度守卫：超出 DB 列宽时 MySQL 严格模式回 500、非严格模式静默截断，
        // 两种都不该让用户碰到。列宽见 wms_work_order：*_no varchar(64)、remark varchar(500)、plan_qty decimal(12,3)
        assertMaxLength(order.getWorkOrderNo(), 64, "工单号");
        assertMaxLength(order.getBatchNo(), 64, "批次号");
        assertMaxLength(order.getMesOrderNo(), 64, "MES订单号");
        assertMaxLength(order.getRemark(), 500, "备注");
        // 尾部 0 不算精度：ERP 常见 10.000000 必须放行
        if (order.getPlanQty().stripTrailingZeros().scale() > 3) {
            throw new RuntimeException("计划数量精度不能超过 3 位小数");
        }
        if (order.getPlanQty().compareTo(new BigDecimal("999999999.999")) > 0) {
            throw new RuntimeException("计划数量超出可录入范围");
        }
    }

    /**
     * 已投产（有入库记录）的工单，推送不得改写物料/产品类型，也不得把计划数量降到已入库量以下。
     *
     * <p>ERP 是主数据的权威来源，但**已经发生的生产与入库**是既成事实：换物料会让库存记录
     * 与工单对不上，把计划量降到已入库量以下会让「剩余可入库量」变成负数。
     */
    private void assertProductionFactsUntouched(WmsWorkOrder existing, WmsMaterial material,
                                                BigDecimal newPlanQty, String newProductType) {
        BigDecimal inbounded = inboundMapper.sumInboundedQty(existing.getWorkOrderNo());
        if (inbounded == null || inbounded.signum() <= 0) {
            return;
        }
        boolean materialChanged = material != null
                && !Objects.equals(existing.getMaterialId(), material.getId());
        boolean productTypeChanged = oConvertUtils.isNotEmpty(newProductType)
                && !Objects.equals(existing.getProductType(), newProductType);
        if (materialChanged || productTypeChanged) {
            throw new RuntimeException("工单已投料/入库，不允许变更物料或产品类型");
        }
        if (newPlanQty != null && newPlanQty.compareTo(inbounded) < 0) {
            throw new RuntimeException("计划数量不能小于已入库数量 "
                    + inbounded.stripTrailingZeros().toPlainString());
        }
    }

    private void assertMaxLength(String value, int maxLength, String label) {
        if (value != null && value.length() > maxLength) {
            throw new RuntimeException(label + "长度不能超过 " + maxLength);
        }
    }
}
