package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsOperationReportDraftService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工序汇报定时同步：开关闸门、滚动窗口、异常吞掉不影响任务链。
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
    void usesRollingWindowEndingToday() {
        IWmsOperationReportDraftService draftService = mock(IWmsOperationReportDraftService.class);
        when(draftService.ingest(anyString(), anyString(), anyInt()))
                .thenReturn(new WmsOperationReportSyncResultVO());

        new WmsOperationReportSyncJob(draftService, null, null).syncForTest();

        // 默认回看 3 天：今天往前推 2 天 ~ 今天
        LocalDate today = LocalDate.now();
        verify(draftService).ingest(eq(today.minusDays(2).toString()), eq(today.toString()), anyInt());
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
