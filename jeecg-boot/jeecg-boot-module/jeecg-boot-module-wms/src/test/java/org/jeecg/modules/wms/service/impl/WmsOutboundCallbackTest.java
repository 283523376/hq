package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库设备回执：幂等认领、全部完成转待复核、失败转异常并告警。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundCallbackTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "detailMapper", mock(WmsOutboundDetailMapper.class));
        WmsTestSupport.inject(service, "inventoryMapper", mock(WmsInventoryMapper.class));
        WmsTestSupport.inject(service, "locationMapper", mock(WmsLocationMapper.class));
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
        when(orderMapper.update(any(), any())).thenReturn(1);
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("LL1")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_PICK)
                .setStatus(WmsConstants.OUTBOUND_STATUS_EXECUTING);
        order.setId("o1");
        return order;
    }

    private WmsOutboundAllocation allocation(String status) {
        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("o1").setTaskNo("T1").setTaskStatus(status);
        allocation.setId("a1");
        return allocation;
    }

    @Test
    void finished_claimsAllocationAndMovesOrderToPendingReview() {
        when(allocationMapper.selectByTaskNo("T1")).thenReturn(allocation(WmsConstants.TASK_STATUS_ACCEPTED));
        when(allocationMapper.claimFinished("T1")).thenReturn(1);
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation(WmsConstants.TASK_STATUS_FINISHED)));
        when(orderMapper.selectById("o1")).thenReturn(order());

        assertTrue(service.onTaskFeedback("T1", "C1", "FINISHED", null, false));

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }

    @Test
    void finished_twice_isIdempotent() {
        when(allocationMapper.selectByTaskNo("T1")).thenReturn(allocation(WmsConstants.TASK_STATUS_ACCEPTED));
        when(allocationMapper.claimFinished("T1")).thenReturn(0);

        assertFalse(service.onTaskFeedback("T1", "C1", "FINISHED", null, false));
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void partialFinished_keepsExecuting() {
        WmsOutboundAllocation other = allocation(WmsConstants.TASK_STATUS_ACCEPTED);
        other.setId("a2");
        when(allocationMapper.selectByTaskNo("T1")).thenReturn(allocation(WmsConstants.TASK_STATUS_ACCEPTED));
        when(allocationMapper.claimFinished("T1")).thenReturn(1);
        when(allocationMapper.selectList(any())).thenReturn(List.of(
                allocation(WmsConstants.TASK_STATUS_FINISHED), other));
        when(orderMapper.selectById("o1")).thenReturn(order());

        service.onTaskFeedback("T1", "C1", "FINISHED", null, false);

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXECUTING, captor.getValue().getStatus());
    }

    @Test
    void lateFailed_afterOrderCompleted_doesNotAlert() {
        when(allocationMapper.selectByTaskNo("T1")).thenReturn(allocation(WmsConstants.TASK_STATUS_FINISHED));
        when(orderMapper.selectById("o1")).thenReturn(order().setStatus(WmsConstants.OUTBOUND_STATUS_COMPLETED));
        // 条件更新未命中（单据已不是可迁移状态）
        when(orderMapper.update(any(), any())).thenReturn(0);

        service.onTaskFeedback("T1", "C1", "FAILED", "迟到回执", false);

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }

    @Test
    void failed_movesOrderToExceptionAndAlerts() {
        when(allocationMapper.selectByTaskNo("T1")).thenReturn(allocation(WmsConstants.TASK_STATUS_ACCEPTED));
        when(allocationMapper.markFailed("T1", "设备卡箱")).thenReturn(1);
        when(orderMapper.selectById("o1")).thenReturn(order());

        service.onTaskFeedback("T1", "C1", "FAILED", "设备卡箱", false);

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION, captor.getValue().getStatus());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE, message.getValue().getTitle());
    }
}
