package org.jeecg.modules.wms.controller;

import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.service.IWmsProductionTaskService;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库控制器分支单测：良品=0 即时同步、良品>0 异步下发。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionControllerTest {

    private IWmsProductionInboundService service;
    private IWmsProductionTaskService taskService;
    private IWmsProductionSyncService syncService;
    private WmsProductionController controller;

    @BeforeEach
    void setUp() {
        service = mock(IWmsProductionInboundService.class);
        IWmsKingdeeWorkOrderSyncService kingdeeWorkOrderSyncService = mock(IWmsKingdeeWorkOrderSyncService.class);
        taskService = mock(IWmsProductionTaskService.class);
        syncService = mock(IWmsProductionSyncService.class);
        controller = new WmsProductionController();
        WmsTestSupport.inject(controller, "service", service);
        WmsTestSupport.inject(controller, "kingdeeWorkOrderSyncService", kingdeeWorkOrderSyncService);
        WmsTestSupport.inject(controller, "taskService", taskService);
        WmsTestSupport.inject(controller, "syncService", syncService);
    }

    private WmsProductionSubmitDTO dto(String good) {
        WmsProductionSubmitDTO dto = new WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO1");
        dto.setGoodQty(new BigDecimal(good));
        dto.setBadQty(BigDecimal.ZERO);
        dto.setCarrierCode("C1");
        return dto;
    }

    private WmsProductionInbound record(String good) {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        record.setInboundNo("SC1");
        record.setGoodQty(new BigDecimal(good));
        record.setStatus("0".equals(good) ? "已入库" : "待上架");
        return record;
    }

    @Test
    void submit_goodQtyZero_triggersImmediateSync() {
        when(service.submit(any())).thenReturn(record("0"));
        when(service.getById("id1")).thenReturn(record("0"));
        when(syncService.syncInbound("id1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        controller.submit(dto("0"));

        verify(syncService).syncInbound("id1", true);
        verify(service).persistSyncResult(org.mockito.ArgumentMatchers.eq("id1"), any());
        verify(taskService, never()).dispatchInboundAsync(anyString());
    }

    @Test
    void submit_goodQtyPositive_usesAsyncDispatch() {
        when(service.submit(any())).thenReturn(record("1"));
        when(service.getById("id1")).thenReturn(record("1"));

        controller.submit(dto("1"));

        verify(taskService).dispatchInboundAsync("id1");
        verify(syncService, never()).syncInbound(anyString(), anyBoolean());
    }

    @Test
    void forceInbound_delegatesAndReturnsResult() {
        when(taskService.forceInbound("id1")).thenReturn(WmsTaskDispatchResult.ok());

        org.jeecg.common.api.vo.Result<WmsTaskDispatchResult> result = controller.forceInbound("id1");

        assertEquals(200, result.getCode());
        verify(taskService).forceInbound("id1");
    }
}
