package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsOperationReportDraftService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工序汇报定时同步：开关闸门、水位线窗口、异常吞掉不影响任务链。
 */
class WmsOperationReportSyncJobTest {

    @Test
    void skipsWhenAutoSyncOff() {
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        IWmsSystemConfigService config = mock(IWmsSystemConfigService.class);
        when(config.getBool("sync.auto", true)).thenReturn(false);

        new WmsOperationReportSyncJob(draftService, config, mock(IWmsKingdeeAuthService.class)).syncForTest();

        verify(draftService, never()).ingest(anyString(), anyString(), anyInt());
    }

    @Test
    void skipsWhenKingdeeNotConfigured() {
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        IWmsKingdeeAuthService auth = mock(IWmsKingdeeAuthService.class);
        when(auth.isConfigured()).thenReturn(false);

        new WmsOperationReportSyncJob(draftService, null, auth).syncForTest();

        verify(draftService, never()).ingest(anyString(), anyString(), anyInt());
    }

    @Test
    void usesWatermarkWindowWhenNoLog() {
        // 没有水位线（首次运行）→ 回看 initial-days（默认 7 天），且用 FModifyDate 的 datetime 边界
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        IWmsSyncLogService syncLog = mock(IWmsSyncLogService.class);
        when(syncLog.latestSuccessTimeMillis(anyString())).thenReturn(0L);
        when(draftService.ingest(anyString(), anyString(), anyInt()))
                .thenReturn(new WmsOperationReportSyncResultVO());

        new WmsOperationReportSyncJob(draftService, null, null, syncLog).syncForTest();

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> end = ArgumentCaptor.forClass(String.class);
        verify(draftService).ingest(start.capture(), end.capture(), anyInt());
        org.junit.jupiter.api.Assertions.assertTrue(
                start.getValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"),
                "起点必须是 datetime，实际: " + start.getValue());
        java.time.LocalDateTime from = java.time.LocalDateTime.parse(start.getValue().replace(' ', 'T'));
        long days = java.time.Duration.between(from, java.time.LocalDateTime.now()).toDays();
        org.junit.jupiter.api.Assertions.assertEquals(7, days, "默认回看 7 天");
    }

    @Test
    void usesLastSuccessAsWatermark() {
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        IWmsSyncLogService syncLog = mock(IWmsSyncLogService.class);
        long lastSuccess = System.currentTimeMillis() - 3L * 3600 * 1000;
        when(syncLog.latestSuccessTimeMillis(anyString())).thenReturn(lastSuccess);
        when(draftService.ingest(anyString(), anyString(), anyInt()))
                .thenReturn(new WmsOperationReportSyncResultVO());

        new WmsOperationReportSyncJob(draftService, null, null, syncLog).syncForTest();

        ArgumentCaptor<String> start = ArgumentCaptor.forClass(String.class);
        verify(draftService).ingest(start.capture(), anyString(), anyInt());
        java.time.LocalDateTime from = java.time.LocalDateTime.parse(start.getValue().replace(' ', 'T'));
        long hours = java.time.Duration.between(from, java.time.LocalDateTime.now()).toHours();
        // 水位线 3 小时前 − 回看 1 天 ≈ 27 小时前
        org.junit.jupiter.api.Assertions.assertEquals(27, hours, "起点应为水位线前 1 天");
    }

    @Test
    void windowFallsBackToInitialDaysWhenNoWatermark() {
        Date now = new Date(1793300000000L);
        String[] w = WmsOperationReportSyncJob.window(null, now, 1, 30, 7);
        String expectedStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(now.getTime() - 7L * 86400000L));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStart, w[0]);
    }

    @Test
    void windowStartsBeforeWatermarkByBackfillDays() {
        Date now = new Date(1793300000000L);
        Date watermark = new Date(now.getTime() - 2L * 3600000L);
        String[] w = WmsOperationReportSyncJob.window(watermark, now, 1, 30, 7);
        String expectedStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(watermark.getTime() - 1L * 86400000L));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStart, w[0]);
    }

    @Test
    void windowClampedToMaxWindowDays() {
        Date now = new Date(1793300000000L);
        Date watermark = new Date(now.getTime() - 100L * 86400000L);
        String[] w = WmsOperationReportSyncJob.window(watermark, now, 1, 30, 7);
        String expectedStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(now.getTime() - 30L * 86400000L));
        org.junit.jupiter.api.Assertions.assertEquals(expectedStart, w[0]);
        org.junit.jupiter.api.Assertions.assertEquals(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now), w[1]);
    }

    @Test
    void swallowsIngestFailure() {
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        when(draftService.ingest(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("金蝶超时"));

        // 不应抛出：单轮失败交给下一轮窗口覆盖
        new WmsOperationReportSyncJob(draftService, null, null).syncForTest();

        verify(draftService).ingest(anyString(), anyString(), anyInt());
    }
}
