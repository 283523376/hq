package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 收料（RK-001 step2）单测——不启 Spring、不连库。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsInboundServiceImplTest {

    private IWmsOrderService orderService;
    private IWmsMaterialService materialService;
    private IWmsMaterialCategoryService categoryService;
    private WmsInboundDetailMapper detailMapper;
    private WmsInspectionTaskMapper inspectionTaskMapper;
    private WmsInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderService = mock(IWmsOrderService.class);
        materialService = mock(IWmsMaterialService.class);
        categoryService = mock(IWmsMaterialCategoryService.class);
        detailMapper = mock(WmsInboundDetailMapper.class);
        inspectionTaskMapper = mock(WmsInspectionTaskMapper.class);

        service = new WmsInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mock(org.jeecg.modules.wms.mapper.WmsInboundOrderMapper.class));
        WmsTestSupport.inject(service, "orderService", orderService);
        WmsTestSupport.inject(service, "materialService", materialService);
        WmsTestSupport.inject(service, "categoryService", categoryService);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "inspectionTaskMapper", inspectionTaskMapper);
    }

    /** 构造一个已 stub 单号/任务号、采购订单与半成品物料就绪的服务。 */
    private void stubHappyPath(String categoryType) {
        WmsInboundServiceImpl spyService = org.mockito.Mockito.spy(service);
        doReturn("SL202610190001").when(spyService).nextOrderNo("SL");
        doReturn("JY202610190001").when(spyService).nextTaskNo("JY");
        WmsOrder po = new WmsOrder().setOrderNo("PO001").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setSupplierId("sup1").setSupplierCode("SUP001").setSupplierName("供应商一");
        when(orderService.getPurchaseOrder("PO001")).thenReturn(po);
        WmsMaterial mat = new WmsMaterial();
        mat.setId("mat1");
        mat.setMaterialCode("M001");
        mat.setMaterialName("电路板");
        mat.setCategoryId("cat1");
        when(materialService.getById("mat1")).thenReturn(mat);
        WmsMaterialCategory cat = new WmsMaterialCategory();
        cat.setId("cat1");
        cat.setCategoryType(categoryType);
        when(categoryService.getById("cat1")).thenReturn(cat);
        service = spyService;
    }

    private WmsReceiveDTO dto(boolean damaged) {
        WmsReceiveDTO dto = new WmsReceiveDTO();
        dto.setPurchaseOrderNo("PO001");
        dto.setPackageDamaged(damaged);
        WmsReceiveDTO.Item item = new WmsReceiveDTO.Item();
        item.setMaterialId("mat1");
        item.setQuantity(new BigDecimal("10"));
        item.setBatchNo("B20261019");
        dto.setDetails(Collections.singletonList(item));
        return dto;
    }

    @Test
    void receiveCreatesPendingInspectOrder() {
        stubHappyPath(WmsConstants.CATEGORY_TYPE_HALF_FINISHED);

        WmsInboundOrder order = service.receive(dto(false));

        assertEquals("SL202610190001", order.getOrderNo());
        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_INSPECT, order.getInboundStatus());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertEquals(0, order.getRetryCount());
        assertEquals("sup1", order.getSupplierId());
        verify(detailMapper).insert(any(org.jeecg.modules.wms.entity.WmsInboundDetail.class));
        ArgumentCaptor<org.jeecg.modules.wms.entity.WmsInspectionTask> cap =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsInspectionTask.class);
        verify(inspectionTaskMapper).insert(cap.capture());
        assertEquals("JY202610190001", cap.getValue().getTaskNo());
        assertEquals(WmsConstants.INSPECT_STATUS_PENDING, cap.getValue().getInspectStatus());
    }

    @Test
    void receiveRejectedWhenPackageDamaged() {
        stubHappyPath(WmsConstants.CATEGORY_TYPE_HALF_FINISHED);

        WmsInboundOrder order = service.receive(dto(true));

        assertEquals(WmsConstants.INBOUND_STATUS_REJECTED, order.getInboundStatus());
        assertTrue(order.getRejectReason().contains("外包装损坏"));
        verify(inspectionTaskMapper, never()).insert(any(org.jeecg.modules.wms.entity.WmsInspectionTask.class));
    }

    @Test
    void receiveFailsWhenPurchaseOrderNotFound() {
        when(orderService.getPurchaseOrder("NOPE")).thenReturn(null);
        WmsReceiveDTO dto = dto(false);
        dto.setPurchaseOrderNo("NOPE");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.receive(dto));
        assertEquals("采购订单不存在", ex.getMessage());
    }

    @Test
    void receiveFailsWhenMaterialNotFound() {
        stubHappyPath(WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        when(materialService.getById("mat1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.receive(dto(false)));
        assertEquals("物料不存在", ex.getMessage());
    }

    @Test
    void receiveFailsWhenCategoryTypeNotSupported() {
        stubHappyPath("成品");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.receive(dto(false)));
        assertTrue(ex.getMessage().contains("类型不支持采购入库"));
    }

    @Test
    void receiveFailsWhenDetailsEmpty() {
        WmsReceiveDTO dto = dto(false);
        dto.setDetails(Arrays.asList());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.receive(dto));
        assertEquals("收料明细不能为空", ex.getMessage());
    }
}
