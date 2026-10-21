package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundAllocator;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 分配编排：成功置已分配；失败置异常待人工并告警。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundAllocateServiceTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private IWmsOutboundAllocator allocator;
    private ISysBaseAPI sysBaseAPI;
    private org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper allocationMapper;
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        allocator = mock(IWmsOutboundAllocator.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "allocator", allocator);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
        allocationMapper = org.mockito.Mockito.mock(
                org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper.class);
        inventoryMapper = org.mockito.Mockito.mock(
                org.jeecg.modules.wms.mapper.WmsInventoryMapper.class);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "locationMapper",
                org.mockito.Mockito.mock(org.jeecg.modules.wms.mapper.WmsLocationMapper.class));
        when(orderMapper.update(any(), any())).thenReturn(1);
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialId("m1").setMaterialCode("PBC001")
                .setRequiredQty(new BigDecimal("10"));
        detail.setId("d1");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail));
    }

    private WmsOutboundOrder order(String status) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("LL1")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_PICK).setStatus(status);
        order.setId("o1");
        return order;
    }

    @Test
    void allocate_success_setsAllocated() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE));

        service.allocate("o1");

        // 并发认领用的是 UpdateWrapper（实体为 null），条件是 status IN ('待分配','异常待人工')
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsOutboundOrder>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "认领条件缺少状态：" + sql);
        verify(allocator).allocate(any(WmsOutboundOrder.class), any());
    }

    @Test
    void allocate_failure_setsExceptionAndAlerts() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE));
        doThrow(new RuntimeException("库存不足：PBC001 需求 10，可用 5"))
                .when(allocator).allocate(any(WmsOutboundOrder.class), any());

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.allocate("o1"));

        assertEquals("库存不足：PBC001 需求 10，可用 5", e.getMessage());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        // 第一次是并发认领（已分配），第二次是失败回写（异常待人工）
        verify(orderMapper, org.mockito.Mockito.times(2)).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION,
                captor.getAllValues().get(captor.getAllValues().size() - 1).getStatus());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_STOCK_ALERT_TITLE, message.getValue().getTitle());
    }

    /** 并发/双击：条件认领未命中时必须直接拒绝，不能进入分配。 */
    @Test
    void allocate_claimFails_throwsWithoutAllocating() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE));
        when(orderMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.allocate("o1"));

        assertEquals("单据正在分配或状态已变化，请刷新后重试", e.getMessage());
        verify(allocator, org.mockito.Mockito.never()).allocate(any(), any());
    }

    /** 异常待人工重新分配：必须先释放旧锁定并删除旧分配行，否则会双倍锁定/双倍扣减。 */
    @Test
    void reallocate_releasesPreviousLocksAndDeletesOldAllocations() {
        WmsOutboundAllocation old = new WmsOutboundAllocation()
                .setOutboundId("o1").setInventoryId("i1").setLocationId("l1")
                .setAllocatedQty(new BigDecimal("5")).setTaskNo("T1")
                .setTaskStatus(WmsConstants.TASK_STATUS_FAILED);
        old.setId("a1");
        when(allocationMapper.selectList(any())).thenReturn(List.of(old));
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_EXCEPTION));

        service.allocate("o1");

        verify(inventoryMapper).releaseLocked("i1", new BigDecimal("5"));
        verify(allocationMapper).delete(any());
        verify(allocator).allocate(any(WmsOutboundOrder.class), any());
    }

    /** 设备任务仍在执行时不允许重新分配，避免实物与账面错位。 */
    @Test
    void reallocate_withRunningTask_throws() {
        WmsOutboundAllocation old = new WmsOutboundAllocation()
                .setOutboundId("o1").setAllocatedQty(new BigDecimal("5"))
                .setTaskNo("T1").setTaskStatus(WmsConstants.TASK_STATUS_EXECUTING);
        old.setId("a1");
        when(allocationMapper.selectList(any())).thenReturn(List.of(old));
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_EXCEPTION));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.allocate("o1"));

        assertEquals("存在执行中的设备任务，请等待任务结束或先关单", e.getMessage());
    }

    @Test
    void allocate_wrongStatus_throws() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_STATUS_COMPLETED));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.allocate("o1"));

        assertEquals("当前状态不允许分配", e.getMessage());
    }
}
