package org.jeecg.modules.wms.job;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Description: 工单 ERP 同步任务单测（配置跳过/两阶段隔离/告警）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsWorkOrderSyncJobTest {

    private IWmsKingdeeWorkOrderSyncService syncService;
    private IWmsSyncLogService logService;
    private IWmsKingdeeAuthService authService;
    private ISysBaseAPI sysBaseAPI;
    private WmsWorkOrderSyncJob job;

    @BeforeEach
    void setUp() {
        syncService = mock(IWmsKingdeeWorkOrderSyncService.class);
        logService = mock(IWmsSyncLogService.class);
        authService = mock(IWmsKingdeeAuthService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        job = new WmsWorkOrderSyncJob(syncService, logService, authService, sysBaseAPI);
    }

    @Test
    void skipsWhenKingdeeNotConfigured() {
        when(authService.isConfigured()).thenReturn(false);
        job.syncForTest();
        verifyNoInteractions(syncService, logService);
    }

    @Test
    void runsIncrementalThenRetryPhases() {
        when(authService.isConfigured()).thenReturn(true);
        when(logService.latestSuccessTimeMillis(anyString())).thenReturn(0L);
        WmsKingdeeWorkOrderSyncResultVO vo = new WmsKingdeeWorkOrderSyncResultVO();
        when(syncService.syncFromKingdee(any(), anyString(), anyString(), anyInt())).thenReturn(vo);
        when(logService.listFailedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of("WO1"));
        when(logService.listExhaustedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(syncService.syncSingleFromKingdee("WO1")).thenReturn(true);

        job.syncForTest();

        verify(syncService, atLeastOnce()).syncFromKingdee(eq(null), anyString(), anyString(), eq(200));
        verify(syncService).syncSingleFromKingdee("WO1");
    }

    @Test
    void retryPhaseStillRunsWhenIncrementalPhaseThrows() {
        when(authService.isConfigured()).thenReturn(true);
        when(logService.latestSuccessTimeMillis(anyString())).thenReturn(0L);
        when(syncService.syncFromKingdee(any(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("接口异常"));
        when(logService.listFailedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of("WO1"));
        when(logService.listExhaustedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(syncService.syncSingleFromKingdee("WO1")).thenReturn(true);

        job.syncForTest();

        verify(syncService).syncSingleFromKingdee("WO1");
    }

    @Test
    void alertsExhaustedBillOnce() {
        when(authService.isConfigured()).thenReturn(true);
        when(logService.latestSuccessTimeMillis(anyString())).thenReturn(0L);
        when(syncService.syncFromKingdee(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new WmsKingdeeWorkOrderSyncResultVO());
        when(logService.listFailedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(logService.listExhaustedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of("WO2"));

        job.syncForTest();

        verify(sysBaseAPI).sendSysAnnouncement(any());
    }

    @Test
    void doesNotAlertWhenNothingExhausted() {
        when(authService.isConfigured()).thenReturn(true);
        when(logService.latestSuccessTimeMillis(anyString())).thenReturn(0L);
        when(syncService.syncFromKingdee(any(), anyString(), anyString(), anyInt()))
                .thenReturn(new WmsKingdeeWorkOrderSyncResultVO());
        when(logService.listFailedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(logService.listExhaustedObjectNos(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        job.syncForTest();

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }
}
