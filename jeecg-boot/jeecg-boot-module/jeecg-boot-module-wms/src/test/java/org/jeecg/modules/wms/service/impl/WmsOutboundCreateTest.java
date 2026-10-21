package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSalesCreateDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库单创建单测：单号前缀、状态、明细落库、工单/物料/数量校验。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundCreateTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsWorkOrderMapper workOrderMapper;
    private WmsMaterialMapper materialMapper;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);

        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "workOrderMapper", workOrderMapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);

        when(orderMapper.insert(any(WmsOutboundOrder.class))).thenReturn(1);
        when(detailMapper.insert(any(WmsOutboundDetail.class))).thenReturn(1);

        WmsWorkOrder workOrder = new WmsWorkOrder()
                .setWorkOrderNo("WO1")
                .setMaterialCode("PBC001")
                .setMaterialName("未加工集成电路板")
                .setProductType("半成品")
                .setBatchNo("B1")
                .setWorkOrderStatus("生产中");
        when(workOrderMapper.selectOne(any())).thenReturn(workOrder);

        WmsMaterial material = new WmsMaterial();
        material.setId("mat1");
        material.setMaterialCode("PBC001");
        material.setMaterialName("未加工集成电路板");
        material.setUnit("PCS");
        when(materialMapper.selectOne(any())).thenReturn(material);
    }

    private WmsOutboundDetailDTO detail(String materialCode, String qty) {
        WmsOutboundDetailDTO dto = new WmsOutboundDetailDTO();
        dto.setMaterialCode(materialCode);
        dto.setRequiredQty(new BigDecimal(qty));
        return dto;
    }

    private WmsOutboundPickCreateDTO pickDto(String workOrderNo, WmsOutboundDetailDTO... details) {
        WmsOutboundPickCreateDTO dto = new WmsOutboundPickCreateDTO();
        dto.setWorkOrderNo(workOrderNo);
        dto.setDetails(List.of(details));
        return dto;
    }

    @Test
    void createPick_persistsOrderAndDetails() {
        WmsOutboundOrder order = service.createPick(pickDto("WO1", detail("PBC001", "10")));

        assertEquals("生产领料", order.getOutboundType());
        assertTrue(order.getOutboundNo().startsWith("LL"));
        assertEquals("待分配", order.getStatus());
        assertEquals("无需同步", order.getSyncStatus());
        assertEquals("手工", order.getRequirementSource());

        ArgumentCaptor<WmsOutboundDetail> captor = ArgumentCaptor.forClass(WmsOutboundDetail.class);
        verify(detailMapper).insert(captor.capture());
        assertEquals("PBC001", captor.getValue().getMaterialCode());
        assertEquals(0, captor.getValue().getRequiredQty().compareTo(new BigDecimal("10")));
        assertEquals(1, captor.getValue().getLineNo());
    }

    @Test
    void createPick_unknownWorkOrder_throws() {
        when(workOrderMapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createPick(pickDto("WO404", detail("PBC001", "1"))));
        assertEquals("工单号无效", e.getMessage());
    }

    @Test
    void createPick_emptyDetails_throws() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createPick(pickDto("WO1")));
        assertEquals("领料明细不能为空", e.getMessage());
    }

    @Test
    void createPick_unknownMaterial_throws() {
        when(materialMapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createPick(pickDto("WO1", detail("NOPE", "1"))));
        assertEquals("物料编码不存在：NOPE", e.getMessage());
    }

    @Test
    void createSales_requiresCustomerAndSetsSaleSyncPending() {
        WmsOutboundSalesCreateDTO dto = new WmsOutboundSalesCreateDTO();
        dto.setSalesOrderNo("SO1");
        dto.setCustomerCode("01.001");
        dto.setCustomerName("湖南屹华科技有限公司");
        dto.setDetails(List.of(detail("PBC001", "5")));

        WmsOutboundOrder order = service.createSales(dto);

        assertTrue(order.getOutboundNo().startsWith("XS"));
        assertEquals("销售出库", order.getOutboundType());
        assertEquals("待同步", order.getSaleSyncStatus());
        assertEquals("无需同步", order.getSyncStatus());
        verify(detailMapper, times(1)).insert(any(WmsOutboundDetail.class));
    }

    @Test
    void createSales_negativeQty_throws() {
        WmsOutboundSalesCreateDTO dto = new WmsOutboundSalesCreateDTO();
        dto.setSalesOrderNo("SO1");
        dto.setCustomerCode("01.001");
        dto.setCustomerName("湖南屹华科技有限公司");
        dto.setDetails(List.of(detail("PBC001", "-1")));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.createSales(dto));
        assertEquals("数量必须大于 0", e.getMessage());
    }
}
