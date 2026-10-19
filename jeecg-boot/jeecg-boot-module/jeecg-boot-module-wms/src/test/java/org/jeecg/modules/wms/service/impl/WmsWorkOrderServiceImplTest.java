package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
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

    @Test
    void remove_referencedWorkOrder_isRejected() {
        when(mapper.selectById("id1")).thenReturn(new WmsWorkOrder().setWorkOrderNo("WO1"));
        when(inboundMapper.selectCount(any())).thenReturn(2L);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.removeWorkOrder("id1"));
        assertTrue(e.getMessage().contains("已被生产入库引用"));
    }

    @Test
    void edit_referencedWorkOrder_cannotChangeMaterialOrProductType() {
        WmsWorkOrder existing = new WmsWorkOrder();
        existing.setId("id1");
        existing.setWorkOrderNo("WO1");
        existing.setMaterialId("m1");
        existing.setProductType("半成品");
        existing.setPlanQty(new BigDecimal("10"));
        existing.setBatchNo("B1");
        when(mapper.selectById("id1")).thenReturn(existing);
        when(inboundMapper.selectCount(any())).thenReturn(1L);

        WmsWorkOrder material2 = new WmsWorkOrder();
        material2.setMaterialId("m2");
        org.jeecg.modules.wms.entity.WmsMaterial material = new org.jeecg.modules.wms.entity.WmsMaterial();
        material.setId("m2");
        material.setMaterialCode("M2");
        material.setMaterialName("物料2");
        when(materialMapper.selectOne(any())).thenReturn(material);

        WmsWorkOrder update = new WmsWorkOrder();
        update.setId("id1");
        update.setWorkOrderNo("WO1");
        update.setMaterialCode("M2");
        update.setProductType("半成品");
        update.setPlanQty(new BigDecimal("10"));
        update.setBatchNo("B1");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.editWorkOrder(update));
        assertEquals("工单已被生产入库引用，不可修改物料或产品类型", e.getMessage());
    }

    @Test
    void push_unknownMaterial_isRejected() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
        dto.setWorkOrderNo("WO1");
        dto.setMaterialCode("NOPE");
        dto.setProductType("半成品");
        dto.setPlanQty(new BigDecimal("10"));
        dto.setBatchNo("B1");
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("物料编码不存在", e.getMessage());
    }
}
