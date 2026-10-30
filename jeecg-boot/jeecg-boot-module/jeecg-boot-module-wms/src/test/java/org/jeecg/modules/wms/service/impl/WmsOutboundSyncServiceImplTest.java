package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.HttpWmsOutboundSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库同步重试：退避后成功、全失败告警、销售订单与出库单同步字段隔离。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundSyncServiceImplTest {

    private HttpWmsOutboundSyncGateway gateway;
    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsOutboundSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = mock(HttpWmsOutboundSyncGateway.class);
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsOutboundSyncServiceImpl(gateway, orderMapper, detailMapper,
                mock(org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper.class),
                mock(org.jeecg.modules.wms.mapper.WmsInventoryMapper.class), sysBaseAPI);
        service.setBackoffMsForTest(new long[] {0L, 0L, 0L});

        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("OUT1").setOutboundType("生产领料");
        order.setId("o1");
        when(orderMapper.selectById("o1")).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of(
                new WmsOutboundDetail().setMaterialCode("M1")));
    }

    @Test
    void sync_retriesThenSucceeds() {
        when(gateway.sync(any(), any()))
                .thenReturn(WmsOrderSyncResult.fail("超时"))
                .thenReturn(WmsOrderSyncResult.fail("超时"))
                .thenReturn(WmsOrderSyncResult.ok());

        WmsOrderSyncAttempt attempt = service.sync("o1", false);

        assertTrue(attempt.isSuccess());
        assertEquals(2, attempt.getRetryCount());
        verify(gateway, times(3)).sync(any(), any());
    }

    @Test
    void sync_allFailures_returnsErrorAndAlertsWhenAnnounce() {
        when(gateway.sync(any(), any())).thenReturn(WmsOrderSyncResult.fail("金蝶不可用"));

        WmsOrderSyncAttempt attempt = service.sync("o1", true);

        assertFalse(attempt.isSuccess());
        assertTrue(attempt.getErrorMessage().contains("金蝶不可用"));
        ArgumentCaptor<MessageDTO> captor = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(captor.capture());
        assertEquals(WmsConstants.OUTBOUND_SYNC_ALERT_TITLE, captor.getValue().getTitle());
    }

    @Test
    void announceFailure_ifNeeded_alertsOnlyAtThreshold() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("OUT1")
                .setSyncStatus(WmsConstants.SYNC_STATUS_FAILED).setLastSyncError("金蝶不可用");
        order.setId("o1");
        order.setSyncFailCount(2);
        when(orderMapper.selectById("o1")).thenReturn(order);

        assertFalse(service.announceFailureIfNeeded("o1"));
        verify(sysBaseAPI, never()).sendSysAnnouncement(any());

        order.setSyncFailCount(WmsConstants.SYNC_ALERT_AFTER_FAILS);
        assertTrue(service.announceFailureIfNeeded("o1"));
        ArgumentCaptor<MessageDTO> captor = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(captor.capture());
        assertEquals(WmsConstants.OUTBOUND_SYNC_ALERT_TITLE, captor.getValue().getTitle());
    }

}
