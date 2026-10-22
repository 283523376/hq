package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产工单扫码查询、推送校验与删除守卫单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsWorkOrderServiceImplTest {

    private WmsWorkOrderMapper mapper;
    private WmsMaterialMapper materialMapper;
    private WmsProductionInboundMapper inboundMapper;
    private WmsWorkOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsWorkOrderMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        service = new WmsWorkOrderServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
    }

    @Test
    void scan_missingWorkOrder_throwsInvalidMessage() {
        when(mapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scan("NOPE"));
        assertEquals("工单号无效", e.getMessage());
    }

    @Test
    void scan_returnsRemainingQty() {
        WmsWorkOrder wo = new WmsWorkOrder().setWorkOrderNo("WO1").setPlanQty(new BigDecimal("100"));
        when(mapper.selectOne(any())).thenReturn(wo);
        when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal("30"));
        assertEquals(0, service.scan("WO1").getRemainingQty().compareTo(new BigDecimal("70")));
    }

    // ---------------------------------------------------------------- BD-002 V2.2 push 合并规则

    private WmsWorkOrderPushDTO baseDto() {
        WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
        dto.setWorkOrderNo("WO1");
        dto.setMaterialCode("M1");
        dto.setProductType("半成品");
        dto.setPlanQty(new BigDecimal("10"));
        dto.setBatchNo("B1");
        return dto;
    }

    private WmsWorkOrder capturedOnSave() {
        org.mockito.ArgumentCaptor<WmsWorkOrder> captor =
                org.mockito.ArgumentCaptor.forClass(WmsWorkOrder.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private WmsWorkOrder capturedOnUpdate() {
        org.mockito.ArgumentCaptor<WmsWorkOrder> captor =
                org.mockito.ArgumentCaptor.forClass(WmsWorkOrder.class);
        org.mockito.Mockito.verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void pushUpdateKeepsErpSourceAndMergesStatus() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("生产中"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("已下达");
        dto.setErpDocumentStatus("C");
        service.push(dto);
        WmsWorkOrder saved = capturedOnUpdate();
        assertEquals("ERP", saved.getSource());
        assertEquals("生产中", saved.getWorkOrderStatus());
        assertEquals("C", saved.getErpDocumentStatus());
    }

    @Test
    void pushUpgradesPendingToReleased() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("待生产"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("ERP");
        dto.setWorkOrderStatus("已下达");
        service.push(dto);
        assertEquals("已下达", capturedOnUpdate().getWorkOrderStatus());
    }

    @Test
    void pushNeverReopensClosed() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已关闭"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        service.push(dto);
        assertEquals("已关闭", capturedOnUpdate().getWorkOrderStatus());
    }

    @Test
    void pushCreateDefaultsMesSource_andErpCreateIsExplicit() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(null);
        service.push(baseDto());
        WmsWorkOrder created = capturedOnSave();
        assertEquals("MES", created.getSource());
        assertEquals("生产中", created.getWorkOrderStatus());

        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.selectOne(any())).thenReturn(null);
        WmsWorkOrderPushDTO erp = baseDto();
        erp.setSource("ERP");
        erp.setWorkOrderStatus("已下达");
        service.push(erp);
        WmsWorkOrder erpCreated = capturedOnSave();
        assertEquals("ERP", erpCreated.getSource());
        assertEquals("已下达", erpCreated.getWorkOrderStatus());
    }

    @Test
    void pushMesUpdateKeepsErpDocumentStatus() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已下达").setErpDocumentStatus("C"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        service.push(dto);
        assertEquals("C", capturedOnUpdate().getErpDocumentStatus());
    }
}
