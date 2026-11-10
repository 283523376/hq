package org.jeecg.modules.wms.controller;

import org.jeecg.modules.wms.dto.WmsTaskFeedbackDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备回执控制器单测：令牌、乐观认领、即时同步（覆盖 WCS 回调）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsCallbackControllerTest {

    private IWmsProductionInboundService productionService;
    private IWmsProductionSyncService syncService;
    private org.jeecg.modules.wms.service.IWmsOutboundService outboundService;
    private WmsRmsCallbackController rmsController;
    private WmsWcsCallbackController wcsController;

    @BeforeEach
    void setUp() {
        productionService = mock(IWmsProductionInboundService.class);
        syncService = mock(IWmsProductionSyncService.class);
        outboundService = mock(org.jeecg.modules.wms.service.IWmsOutboundService.class);

        rmsController = new WmsRmsCallbackController();
        WmsTestSupport.inject(rmsController, "productionService", productionService);
        WmsTestSupport.inject(rmsController, "productionSyncService", syncService);
        WmsTestSupport.inject(rmsController, "outboundService", outboundService);
        WmsTestSupport.inject(rmsController, "callbackToken", "dev-rms-token");

        wcsController = new WmsWcsCallbackController();
        WmsTestSupport.inject(wcsController, "productionService", productionService);
        WmsTestSupport.inject(wcsController, "productionSyncService", syncService);
        WmsTestSupport.inject(wcsController, "outboundService", outboundService);
        WmsTestSupport.inject(wcsController, "callbackToken", "dev-wcs-token");
    }

    @Test
    void outboundTask_isRoutedToOutboundService() {
        when(productionService.findByTaskNo("T1")).thenReturn(null);
        when(outboundService.isOutboundTask("T1")).thenReturn(true);

        Map<String, Object> response = rmsController.taskFeedback("dev-rms-token", feedback("FINISHED"));

        assertEquals(0, response.get("code"));
        verify(outboundService).onTaskFeedback(eq("T1"), eq("C1"), eq("FINISHED"), any(), eq(true));
        verify(syncService, never()).syncInbound(anyString(), anyBoolean());
    }

    private WmsProductionInbound record() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        record.setInboundNo("SC1");
        return record;
    }

    private WmsTaskFeedbackDTO feedback(String status) {
        WmsTaskFeedbackDTO.Detail detail = new WmsTaskFeedbackDTO.Detail();
        detail.setTaskNo("T1");
        detail.setContainerCode("C1");
        detail.setCarrierCode("C1");
        detail.setTaskStatus(status);
        detail.setStackCode("STACK-1");
        WmsTaskFeedbackDTO dto = new WmsTaskFeedbackDTO();
        dto.setDetails(List.of(detail));
        return dto;
    }

    @Test
    void rmsClaimZero_skipsSync() {
        when(productionService.findByTaskNo("T1")).thenReturn(record());
        when(productionService.onTaskFeedback(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(true);
        when(productionService.claimForSync(eq("id1"), any())).thenReturn(0);

        Map<String, Object> response = rmsController.taskFeedback("dev-rms-token", feedback("FINISHED"));

        assertEquals(0, response.get("code"));
        verify(syncService, never()).syncInbound(anyString(), anyBoolean());
    }

    @Test
    void rmsClaimOne_syncsAndPersists() {
        when(productionService.findByTaskNo("T1")).thenReturn(record());
        when(productionService.onTaskFeedback(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(true);
        when(productionService.claimForSync(eq("id1"), any())).thenReturn(1);
        when(syncService.syncInbound("id1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        rmsController.taskFeedback("dev-rms-token", feedback("FINISHED"));

        verify(syncService).syncInbound("id1", true);
        verify(productionService).persistSyncResult(eq("id1"), any());
    }

    @Test
    void badToken_isRejected() {
        Map<String, Object> response = rmsController.taskFeedback("wrong", feedback("FINISHED"));
        assertEquals(1, response.get("code"));
    }

    @Test
    void wcsFinished_claimsAndSyncs() {
        when(productionService.findByTaskNo("T1")).thenReturn(record());
        when(productionService.onTaskFeedback(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(true);
        when(productionService.claimForSync(eq("id1"), any())).thenReturn(1);
        when(syncService.syncInbound("id1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        Map<String, Object> response = wcsController.taskFeedback("dev-wcs-token", feedback("FINISHED"));

        assertEquals(0, response.get("code"));
        verify(syncService).syncInbound("id1", true);
    }
}
