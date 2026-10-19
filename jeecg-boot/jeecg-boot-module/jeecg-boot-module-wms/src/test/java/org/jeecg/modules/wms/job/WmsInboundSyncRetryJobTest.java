package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.service.IWmsInboundSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 入库同步补偿任务单测：单条失败不得中断整轮。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsInboundSyncRetryJobTest {

    private WmsInboundOrder order(String id) {
        WmsInboundOrder o = new WmsInboundOrder();
        o.setId(id);
        o.setOrderNo("SL" + id);
        return o;
    }

    @Test
    void jobClaimsAndResyncsFailedInbounds() {
        IWmsInboundService inboundService = mock(IWmsInboundService.class);
        IWmsInboundSyncService syncService = mock(IWmsInboundSyncService.class);
        when(inboundService.syncStaleBefore()).thenReturn(new Date());
        when(inboundService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(order("1"), order("2")));
        when(inboundService.claimForSync(any(), any())).thenReturn(1);
        when(syncService.syncInbound(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        WmsInboundSyncRetryJob job = new WmsInboundSyncRetryJob(inboundService, syncService);
        job.scanAndResyncForTest();

        verify(syncService, times(2)).syncInbound(any(), anyBoolean());
        verify(inboundService, times(2)).persistSyncResult(any(), any());
    }

    @Test
    void jobSkipsAlreadyClaimedRowsAndContinues() {
        IWmsInboundService inboundService = mock(IWmsInboundService.class);
        IWmsInboundSyncService syncService = mock(IWmsInboundSyncService.class);
        when(inboundService.syncStaleBefore()).thenReturn(new Date());
        when(inboundService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(order("1"), order("2")));
        // 第一行没抢到（已被他人认领）
        when(inboundService.claimForSync(any(), any())).thenReturn(0, 1);
        when(syncService.syncInbound(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        WmsInboundSyncRetryJob job = new WmsInboundSyncRetryJob(inboundService, syncService);
        job.scanAndResyncForTest();

        verify(syncService, times(1)).syncInbound(any(), anyBoolean());
    }

    @Test
    void jobDoesNotAbortRoundOnSingleFailure() {
        IWmsInboundService inboundService = mock(IWmsInboundService.class);
        IWmsInboundSyncService syncService = mock(IWmsInboundSyncService.class);
        when(inboundService.syncStaleBefore()).thenReturn(new Date());
        when(inboundService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(order("1"), order("2"), order("3")));
        when(inboundService.claimForSync(any(), any())).thenReturn(1);
        when(syncService.syncInbound(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0))
                .thenThrow(new RuntimeException("模拟第 2 条炸了"))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        WmsInboundSyncRetryJob job = new WmsInboundSyncRetryJob(inboundService, syncService);

        assertDoesNotThrow(job::scanAndResyncForTest);
        verify(syncService, times(3)).syncInbound(any(), anyBoolean());
    }
}
