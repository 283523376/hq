package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundSampleCreateDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 样品出库建单——类型/状态/明细，且不绑定工单与销售订单（spec §5.2）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsOutboundSampleCreateTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsMaterialMapper materialMapper;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);

        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenReturn(1);
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);
    }

    @Test
    void shouldCreateSampleOrderBoundToNeitherWorkOrderNorSalesOrder() {
        when(materialMapper.selectOne(any())).thenReturn(material());
        WmsOutboundSampleCreateDTO dto = new WmsOutboundSampleCreateDTO();
        dto.setMaterialCode("M1");
        dto.setQty(new BigDecimal("5"));
        dto.setBatchNo("B1");
        dto.setRemark("样品");

        service.createSample(dto);

        ArgumentCaptor<WmsOutboundOrder> orderCaptor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        WmsOutboundOrder saved = orderCaptor.getValue();
        assertEquals(WmsConstants.OUTBOUND_TYPE_SAMPLE, saved.getOutboundType());
        assertEquals(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE, saved.getStatus());
        assertNull(saved.getWorkOrderNo());
        assertNull(saved.getSalesOrderNo());
        assertTrue(saved.getOutboundNo().startsWith(WmsConstants.OUTBOUND_NO_PREFIX_SAMPLE),
                saved.getOutboundNo());

        ArgumentCaptor<WmsOutboundDetail> detailCaptor = ArgumentCaptor.forClass(WmsOutboundDetail.class);
        verify(detailMapper).insert(detailCaptor.capture());
        WmsOutboundDetail detail = detailCaptor.getValue();
        assertEquals("M1", detail.getMaterialCode());
        assertEquals(0, detail.getRequiredQty().compareTo(new BigDecimal("5")));
        assertEquals("B1", detail.getBatchNo());
    }

    @Test
    void shouldRejectNonPositiveQty() {
        WmsOutboundSampleCreateDTO dto = new WmsOutboundSampleCreateDTO();
        dto.setMaterialCode("M1");
        dto.setQty(BigDecimal.ZERO);

        assertThrows(RuntimeException.class, () -> service.createSample(dto));
    }

    @Test
    void shouldRejectUnknownMaterial() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        WmsOutboundSampleCreateDTO dto = new WmsOutboundSampleCreateDTO();
        dto.setMaterialCode("NOPE");
        dto.setQty(BigDecimal.ONE);

        assertThrows(RuntimeException.class, () -> service.createSample(dto));
    }

    private static WmsMaterial material() {
        WmsMaterial material = new WmsMaterial();
        material.setId("mat-1");
        material.setMaterialCode("M1");
        material.setMaterialName("样品物料");
        material.setUnit("PCS");
        return material;
    }
}
