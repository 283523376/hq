package org.jeecg.modules.wms.controller;

import org.jeecg.modules.wms.dto.WmsOutboundSampleCreateDTO;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsSubcontractOrderSyncService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsSubcontractSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 外协/样品出库端点转发单测（spec §5.1 §5.2）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsOutboundControllerSubcontractTest {

    private IWmsOutboundService outboundService;
    private IWmsSubcontractOrderSyncService subcontractSyncService;
    private WmsOutboundController controller;

    @BeforeEach
    void setUp() {
        outboundService = mock(IWmsOutboundService.class);
        subcontractSyncService = mock(IWmsSubcontractOrderSyncService.class);
        controller = new WmsOutboundController();
        WmsTestSupport.inject(controller, "service", outboundService);
        WmsTestSupport.inject(controller, "subcontractOrderSyncService", subcontractSyncService);
    }

    @Test
    void syncSubcontractShouldForwardTop() {
        when(subcontractSyncService.syncFromKingdee(50)).thenReturn(new WmsSubcontractSyncResultVO());

        var result = controller.syncSubcontract(50);

        verify(subcontractSyncService).syncFromKingdee(50);
        assertTrue(result.isSuccess());
    }

    @Test
    void createSampleShouldForwardDto() {
        WmsOutboundSampleCreateDTO dto = new WmsOutboundSampleCreateDTO();
        dto.setMaterialCode("M1");
        dto.setQty(BigDecimal.TEN);
        when(outboundService.createSample(any())).thenReturn(new WmsOutboundOrder());

        var result = controller.createSample(dto);

        verify(outboundService).createSample(dto);
        assertTrue(result.isSuccess());
        assertEquals("创建成功", result.getMessage());
    }
}
