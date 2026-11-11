package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsOutboundConfirmVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: CK-002 确认出库：销售未同步需确认、override 留痕、扣减与差额释放、零数量、关单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundConfirmTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsLocationMapper locationMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsWorkOrderMapper workOrderMapper;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
        WmsTestSupport.inject(service, "workOrderMapper", workOrderMapper);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(allocationMapper.update(any(), any())).thenReturn(1);
        when(detailMapper.updateById(any(WmsOutboundDetail.class))).thenReturn(1);
        when(inventoryMapper.deductLocked(any(), any())).thenReturn(1);
        when(inventoryMapper.releaseLocked(any(), any())).thenReturn(1);
        when(inventoryMapper.selectOne(any())).thenReturn(null);
        when(locationMapper.update(any(), any())).thenReturn(1);
    }

    private WmsOutboundOrder order(String type, String status) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("OUT1").setOutboundType(type)
                .setStatus(status);
        order.setId("o1");
        return order;
    }

    private WmsOutboundAllocation allocation(String reviewStatus, String allocated, String actual) {
        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("o1").setDetailId("d1").setInventoryId("i1").setLocationId("l1")
                .setAllocatedQty(new BigDecimal(allocated)).setActualQty(new BigDecimal(actual))
                .setReviewStatus(reviewStatus);
        allocation.setId("a1");
        return allocation;
    }

    private void stubOrderAndAllocation(WmsOutboundOrder order, WmsOutboundAllocation allocation) {
        when(orderMapper.selectById("o1")).thenReturn(order);
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("PBC001");
        detail.setId("d1");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail));
    }

    @Test
    void confirm_salesOrder_deductsWithoutSaleSyncGate() {
        // CK-002 方向反转后：销售订单来自 ERP，不再有「未同步」门槛，也不记录 override
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_SALES, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_MATCHED, "10", "10"));

        WmsOutboundConfirmVO vo = service.confirm("o1", new WmsOutboundConfirmDTO());

        assertTrue(vo.isConfirmed());
        verify(inventoryMapper).deductLocked("i1", new BigDecimal("10"));
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_COMPLETED, captor.getValue().getStatus());
    }

    @Test
    void confirmPick_deductsAndReleasesLocation() {
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "10"));

        WmsOutboundConfirmVO vo = service.confirmPick("o1", new WmsOutboundConfirmDTO());

        assertTrue(vo.isConfirmed());
        verify(inventoryMapper).deductLocked("i1", new BigDecimal("10"));
        verify(locationMapper).update(any(), any());
    }

    /** D2 最后一道兜底：库存被并发抢先（deductLocked 影响 0 行）→ 置异常 + 告警 + 抛错回滚。 */
    @Test
    void confirm_deductLockedZero_marksExceptionAlertsAndThrows() {
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "10"));
        when(inventoryMapper.deductLocked(eq("i1"), any())).thenReturn(0);

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.confirmPick("o1", new WmsOutboundConfirmDTO()));

        assertTrue(e.getMessage().contains("库存扣减失败"), e.getMessage());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION, captor.getValue().getStatus());
        verify(sysBaseAPI).sendSysAnnouncement(any());
    }

    /** 不变量对照 §2-1：确认必须条件认领——读后写窗口内被并发取消时，0 行 → 抛错回滚。 */
    @Test
    void confirm_staleStatusClaimZero_throwsWithoutCompleting() {
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "10"));
        // 库存扣减先于状态认领执行；认领 0 行抛错 → 事务回滚整笔扣减
        when(orderMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.confirmPick("o1", new WmsOutboundConfirmDTO()));

        assertTrue(e.getMessage().contains("单据状态已变化"), e.getMessage());
        // 认领必须带状态条件：wrapper 的 WHERE 里要有 status IN (待复核)
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsOutboundOrder>> wrapperCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderMapper).update(any(), wrapperCap.capture());
        String sql = wrapperCap.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), sql);
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsOutboundOrder> uw =
                (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsOutboundOrder>) wrapperCap.getValue();
        assertTrue(uw.getParamNameValuePairs().containsValue(WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                "认领条件必须包含 当前状态=待复核");
    }

    @Test
    void confirm_zeroActual_releasesLockWithoutDeduct() {
        // 复核后显式确认「实收 0」才允许不扣库存直接释放锁定
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_MATCHED, "10", "0"));

        WmsOutboundConfirmVO vo = service.confirmPick("o1", new WmsOutboundConfirmDTO());

        assertTrue(vo.isConfirmed());
        verify(inventoryMapper, never()).deductLocked(any(), any());
        verify(inventoryMapper).releaseLocked("i1", new BigDecimal("10"));
    }

    /**
     * 分配后未复核/未传实收数量时，确认必须按分配数量扣库存。
     *
     * <p>回归：曾因 actualQty 为 null 直接按 0 处理，订单被标记完成但库存只释放锁定、
     * 数量原封不动（E2E 实测 quantity 8→8、locked 2→0）。
     */
    @Test
    void confirm_withNullActualQty_defaultsToAllocated() {
        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("o1").setDetailId("d1").setInventoryId("i1").setLocationId("l1")
                .setAllocatedQty(new BigDecimal("10")).setReviewStatus(WmsConstants.REVIEW_STATUS_PENDING);
        allocation.setId("a1");
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW), allocation);

        WmsOutboundConfirmVO vo = service.confirmPick("o1", new WmsOutboundConfirmDTO());

        assertTrue(vo.isConfirmed());
        verify(inventoryMapper).deductLocked("i1", new BigDecimal("10"));
        verify(inventoryMapper, never()).releaseLocked(any(), any());
        // 实收数量按默认值确认后必须回写分配行，避免详情页显示「已分配 2 / 实收 0」
        verify(allocationMapper).update(any(), any());
    }

    @Test
    void confirm_negativeActualQty_isRejected() {
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "10"));
        WmsOutboundConfirmDTO.Item item = new WmsOutboundConfirmDTO.Item();
        item.setAllocationId("a1");
        item.setActualQty(new BigDecimal("-5"));
        WmsOutboundConfirmDTO dto = new WmsOutboundConfirmDTO();
        dto.setDetails(List.of(item));

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.confirmPick("o1", dto));

        assertEquals("实收数量不能为负数", e.getMessage());
        verify(inventoryMapper, never()).deductLocked(any(), any());
    }

    /** 设备已完成但未确认出库时关单：锁定也必须释放，否则库位永久占用。 */
    @Test
    void close_finishedTask_stillReleasesLock() {
        WmsOutboundOrder order = order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        when(orderMapper.selectById("o1")).thenReturn(order);
        WmsOutboundAllocation allocation = allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "0");
        allocation.setTaskNo("T1");
        allocation.setTaskStatus(WmsConstants.TASK_STATUS_FINISHED);
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));

        service.close("o1", "领错料");

        verify(inventoryMapper).releaseLocked("i1", new BigDecimal("10"));
        verify(locationMapper).update(any(), any());
    }

    @Test
    void close_releasesAllLocks() {
        WmsOutboundOrder order = order(WmsConstants.OUTBOUND_TYPE_PICK, WmsConstants.OUTBOUND_STATUS_ALLOCATED);
        when(orderMapper.selectById("o1")).thenReturn(order);
        WmsOutboundAllocation allocation = allocation(WmsConstants.REVIEW_STATUS_PENDING, "10", "10");
        allocation.setTaskNo("T1");
        allocation.setTaskStatus(WmsConstants.TASK_STATUS_ACCEPTED);
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));

        service.close("o1", "客户取消");

        verify(inventoryMapper).releaseLocked("i1", new BigDecimal("10"));
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_CLOSED, captor.getValue().getStatus());
        assertEquals(WmsConstants.SYNC_STATUS_NOT_REQUIRED, captor.getValue().getSyncStatus());
    }

    @Test
    void close_requiresReason() {
        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.close("o1", " "));
        assertEquals("关单原因不能为空", e.getMessage());
    }

    @Test
    void confirmSample_deductsAndMarksSyncNotRequired() {
        stubOrderAndAllocation(order(WmsConstants.OUTBOUND_TYPE_SAMPLE, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW),
                allocation(WmsConstants.REVIEW_STATUS_MATCHED, "10", "10"));

        WmsOutboundConfirmVO vo = service.confirm("o1", new WmsOutboundConfirmDTO());

        assertTrue(vo.isConfirmed());
        verify(inventoryMapper).deductLocked("i1", new BigDecimal("10"));
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_COMPLETED, captor.getValue().getStatus());
        // 样品本期没有回写实现（E5），不得置「待同步」——否则重试任务每 5 分钟刷一次失败
        assertEquals(WmsConstants.SYNC_STATUS_NOT_REQUIRED, captor.getValue().getSyncStatus());
    }

    @Test
    void review_acceptsSampleType() {
        WmsOutboundOrder sampleOrder = order(WmsConstants.OUTBOUND_TYPE_SAMPLE,
                WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        when(orderMapper.selectById("o1")).thenReturn(sampleOrder);

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.review("o1", new org.jeecg.modules.wms.dto.WmsOutboundReviewDTO()));

        // 已通过类型闸门，卡在后续「复核明细不能为空」
        assertEquals("复核明细不能为空", e.getMessage());
    }

    /** 评审 I4：工单在单据生成后才被作废的，确认入口必须拦下（作废即止损）。 */
    @Test
    void confirmPick_cancelledWorkOrder_rejected() {
        WmsOutboundOrder pickOrder = order(WmsConstants.OUTBOUND_TYPE_PICK,
                WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW).setWorkOrderNo("WO-CX");
        stubOrderAndAllocation(pickOrder, allocation(WmsConstants.REVIEW_STATUS_MATCHED, "5", "0"));
        when(workOrderMapper.selectOne(any())).thenReturn(
                new WmsWorkOrder().setWorkOrderNo("WO-CX").setWorkOrderStatus("已作废"));

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.confirmPick("o1", new WmsOutboundConfirmDTO()));

        assertEquals("工单已作废，不能确认出库", e.getMessage());
        // 库存没被扣
        verify(inventoryMapper, never()).deductLocked(any(), any());
    }

    /** 销售出库没有关联工单：不受作废守卫影响，照常确认。 */
    @Test
    void confirm_salesOrderWithoutWorkOrder_unaffected() {
        WmsOutboundOrder salesOrder = order(WmsConstants.OUTBOUND_TYPE_SALES,
                WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        stubOrderAndAllocation(salesOrder, allocation(WmsConstants.REVIEW_STATUS_MATCHED, "5", "5"));

        service.confirm("o1", new WmsOutboundConfirmDTO());

        verify(workOrderMapper, never()).selectOne(any());
        // 走到了扣减就说明守卫没有误伤销售出库
        verify(inventoryMapper).deductLocked(any(), any());
    }
}
