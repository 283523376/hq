package org.jeecg.modules.wms.controller;

import org.jeecg.modules.wms.dto.WmsTaskFeedbackDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备回执 → 即时回写 的开关顺序单测。
 *
 * <p>曾经写成「先 claimForSync 认领（置同步中）再判断开关」，自动同步关闭时认领完
 * 又不推送，记录会永远卡在「同步中」——手工同步也会被「记录正在同步中」挡回去。
 * 所以：开关判断必须早于认领。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
class WmsRmsCallbackControllerTest {

    private IWmsProductionInboundService productionService;
    private IWmsProductionSyncService productionSyncService;
    private WmsRmsCallbackController controller;

    @BeforeEach
    void setUp() {
        productionService = mock(IWmsProductionInboundService.class);
        productionSyncService = mock(IWmsProductionSyncService.class);
        controller = new WmsRmsCallbackController();
        WmsTestSupport.inject(controller, "productionService", productionService);
        WmsTestSupport.inject(controller, "productionSyncService", productionSyncService);
        WmsTestSupport.inject(controller, "outboundService", mock(IWmsOutboundService.class));
        WmsTestSupport.inject(controller, "callbackToken", "dev-rms-token");
    }

    private WmsTaskFeedbackDTO feedback() {
        WmsTaskFeedbackDTO.Detail detail = new WmsTaskFeedbackDTO.Detail();
        detail.setTaskNo("RMS1");
        detail.setTaskStatus("FINISHED");
        WmsTaskFeedbackDTO dto = new WmsTaskFeedbackDTO();
        dto.setDetails(List.of(detail));
        return dto;
    }

    private void stubCompletedRecord() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        when(productionService.findByTaskNo("RMS1")).thenReturn(record);
        when(productionService.onTaskFeedback(anyString(), any(), anyString(), any(), any(), anyBoolean()))
                .thenReturn(true);
        when(productionService.syncStaleBefore()).thenReturn(new Date());
    }

    @Test
    void autoSyncDisabled_shouldNotClaimAndNotSync() {
        IWmsSystemConfigService configService = mock(IWmsSystemConfigService.class);
        when(configService.getBool("sync.auto", true)).thenReturn(false);
        WmsTestSupport.inject(controller, "systemConfigService", configService);
        stubCompletedRecord();

        controller.taskFeedback("dev-rms-token", feedback());

        verify(productionService, never()).claimForSync(anyString(), any());
        verify(productionSyncService, never()).syncInbound(anyString(), anyBoolean());
    }

    @Test
    void autoSyncEnabled_shouldClaimThenSync() {
        IWmsSystemConfigService configService = mock(IWmsSystemConfigService.class);
        when(configService.getBool("sync.auto", true)).thenReturn(true);
        WmsTestSupport.inject(controller, "systemConfigService", configService);
        stubCompletedRecord();
        when(productionService.claimForSync(anyString(), any())).thenReturn(1);
        when(productionSyncService.syncInbound("id1", true))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        controller.taskFeedback("dev-rms-token", feedback());

        verify(productionService).claimForSync(anyString(), any());
        verify(productionSyncService).syncInbound("id1", true);
        verify(productionService).persistSyncResult(anyString(), any());
    }
}
