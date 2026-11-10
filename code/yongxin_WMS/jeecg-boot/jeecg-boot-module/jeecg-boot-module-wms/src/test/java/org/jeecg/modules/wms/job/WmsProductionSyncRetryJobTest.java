package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.service.IWmsProductionTaskService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库补偿任务单测：未下发重新下发 + 同步候选续跑。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionSyncRetryJobTest {

    private IWmsProductionInboundService inboundService;
    private IWmsProductionSyncService syncService;
    private IWmsProductionTaskService taskService;
    private WmsProductionSyncRetryJob job;

    @BeforeEach
    void setUp() {
        inboundService = mock(IWmsProductionInboundService.class);
        syncService = mock(IWmsProductionSyncService.class);
        taskService = mock(IWmsProductionTaskService.class);
        job = new WmsProductionSyncRetryJob(inboundService, syncService, taskService);
    }

    private WmsProductionInbound record() {
        WmsProductionInbound r = new WmsProductionInbound();
        r.setId("id1");
        r.setInboundNo("SC1");
        return r;
    }

    @Test
    void job_alertsWhenCumulativeFailuresReachThreshold() {
        when(inboundService.selectUndispatchedBefore(any(), anyInt())).thenReturn(List.of());
        when(inboundService.selectSyncCandidates(any(), anyInt())).thenReturn(List.of(record()));
        when(inboundService.claimForSync(any(), any())).thenReturn(1);
        when(syncService.syncInbound("id1", false))
                .thenReturn(new WmsOrderSyncAttempt(false, "ERP 500", 3));
        WmsProductionInbound updated = record();
        updated.setSyncFailCount(3);
        when(inboundService.getById("id1")).thenReturn(updated);

        job.scanAndResyncForTest();

        verify(syncService).alertRepeatedFailure(updated, "ERP 500");
    }

    @Test
    void job_dispatchesUndispatchedAndResyncs() {
        when(inboundService.selectUndispatchedBefore(any(), anyInt())).thenReturn(List.of(record()));
        when(inboundService.selectSyncCandidates(any(), anyInt())).thenReturn(List.of(record()));
        when(inboundService.claimForSync(any(), any())).thenReturn(1);
        when(syncService.syncInbound("id1", false)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        job.scanAndResyncForTest();

        verify(taskService).dispatchInbound("id1");
        verify(inboundService).claimForSync(eq("id1"), any());
        verify(inboundService).persistSyncResult(eq("id1"), any());
    }

    /**
     * 自动同步关闭（sync.auto=false）时本轮必须直接跳过：
     * 由业务页面的手工「同步金蝶」按钮接管，后台不再偷偷推送。
     */
    @Test
    void job_skipsEntireRoundWhenAutoSyncDisabled() {
        IWmsSystemConfigService configService = mock(IWmsSystemConfigService.class);
        when(configService.getBool("sync.auto", true)).thenReturn(false);
        WmsTestSupport.inject(job, "systemConfigService", configService);

        job.scanAndResyncForTest();

        verify(inboundService, never()).selectUndispatchedBefore(any(), anyInt());
        verify(inboundService, never()).selectSyncCandidates(any(), anyInt());
        verify(syncService, never()).syncInbound(any(), eq(false));
    }

    @Test
    void job_reconcilesReportSourcedInbounds() {
        // 方案 B：工序汇报来源的单据等车间在金蝶汇报入库，每轮尝试对账回填 ERP 单号
        when(inboundService.selectUndispatchedBefore(any(), anyInt())).thenReturn(List.of());
        when(inboundService.selectSyncCandidates(any(), anyInt())).thenReturn(List.of());
        WmsProductionInbound reported = record();
        reported.setErpEntryKey("GXHB659871#0#10");
        reported.setErpBillNo("GXHB659871");
        when(inboundService.selectReportSourcedPendingReconcile(anyInt())).thenReturn(List.of(reported));
        when(syncService.reconcileReportSourced(reported))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        job.scanAndResyncForTest();

        verify(syncService).reconcileReportSourced(reported);
    }

    @Test
    void job_skipsReconcileWhenAutoSyncOff() {
        org.jeecg.modules.wms.service.IWmsSystemConfigService configService =
                mock(org.jeecg.modules.wms.service.IWmsSystemConfigService.class);
        // 闸门读的是 getBool(key, 默认 true)，stub getString 不会生效（曾导致用例靠 mock 默认值假绿）
        when(configService.getBool(any(), eq(true))).thenReturn(false);
        WmsProductionSyncRetryJob offlineJob =
                new WmsProductionSyncRetryJob(inboundService, syncService, taskService, configService);

        offlineJob.scanAndResyncForTest();

        // 证明确实走到了闸门判断（而不是靠 mock 默认值碰巧通过）
        verify(configService).getBool(any(), eq(true));
        verify(inboundService, never()).selectReportSourcedPendingReconcile(anyInt());
    }
}
