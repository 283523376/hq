package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库补偿任务：超时未下发重发、出库同步、销售订单同步、单条失败不中断。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundSyncRetryJobTest {

    private IWmsOutboundSyncService syncService;
    private IWmsOutboundTaskService taskService;
    private WmsOutboundSyncRetryJob job;

    @BeforeEach
    void setUp() {
        syncService = mock(IWmsOutboundSyncService.class);
        taskService = mock(IWmsOutboundTaskService.class);
        job = new WmsOutboundSyncRetryJob(syncService, taskService);
        when(syncService.syncStaleBefore()).thenReturn(new Date());
        when(syncService.selectUndispatchedBefore(any(), anyInt())).thenReturn(List.of(order("o1")));
        when(syncService.selectSyncCandidates(any(), anyInt())).thenReturn(List.of(order("o2")));
        when(syncService.selectSaleSyncCandidates(any(), anyInt())).thenReturn(List.of(order("o3")));
        when(syncService.claimForSync(eq("o2"), any())).thenReturn(1);
        when(syncService.claimSaleForSync(eq("o3"), any())).thenReturn(1);
        when(syncService.sync(eq("o2"), eq(false))).thenReturn(new WmsOrderSyncAttempt(true, null, 0));
        when(syncService.syncSaleOrder(eq("o3"), eq(false)))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));
    }

    private WmsOutboundOrder order(String id) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("OUT-" + id);
        order.setId(id);
        return order;
    }

    @Test
    void job_redispatchesStaleAllocated() {
        job.scanAndResyncForTest();

        verify(taskService).dispatch("o1");
        verify(syncService).sync("o2", false);
        verify(syncService).syncSaleOrder("o3", false);
        verify(syncService).persistResult(eq("o2"), any());
        verify(syncService).persistSaleResult(eq("o3"), any());
    }

    @Test
    void job_singleFailureDoesNotStopRound() {
        doThrow(new RuntimeException("同步异常")).when(syncService).sync(eq("o2"), eq(false));

        job.scanAndResyncForTest();

        verify(syncService).syncSaleOrder("o3", false);
    }

    @Test
    void job_failedSync_asksServiceToAnnounceAtThreshold() {
        when(syncService.sync(eq("o2"), eq(false)))
                .thenReturn(new WmsOrderSyncAttempt(false, "金蝶不可用", 3));

        job.scanAndResyncForTest();

        verify(syncService).announceFailureIfNeeded("o2", false);
        verify(syncService).syncSaleOrder("o3", false);
    }

    @Test
    void job_claimZero_skipsRecord() {
        when(syncService.claimForSync(eq("o2"), any())).thenReturn(0);

        job.scanAndResyncForTest();

        verify(syncService, never()).sync(eq("o2"), anyBoolean());
    }
}
