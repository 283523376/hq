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

    // ------------------------------------------------------------ 自动分配（RK-001 step5）

    private org.jeecg.modules.wms.service.IWmsLocationService locationService;

    private void stubAllocateEnv(String orderStatus,
                                 java.util.List<org.jeecg.modules.wms.entity.WmsInboundDetail> rows) {
        locationService = mock(org.jeecg.modules.wms.service.IWmsLocationService.class);
        WmsTestSupport.inject(service, "locationService", locationService);
        WmsInboundServiceImpl spyService = org.mockito.Mockito.spy(service);
        org.jeecg.modules.wms.entity.WmsInboundOrder order = new org.jeecg.modules.wms.entity.WmsInboundOrder();
        order.setId("ib1");
        order.setInboundStatus(orderStatus);
        doReturn(order).when(spyService).getById("ib1");
        doReturn(true).when(spyService).updateById(any(org.jeecg.modules.wms.entity.WmsInboundOrder.class));
        when(detailMapper.selectList(any())).thenReturn(rows);
        service = spyService;
    }

    private org.jeecg.modules.wms.entity.WmsInboundDetail row(String id, String matName, String categoryType) {
        org.jeecg.modules.wms.entity.WmsInboundDetail d = new org.jeecg.modules.wms.entity.WmsInboundDetail();
        d.setId(id);
        d.setMaterialId("m-" + id);
        d.setMaterialName(matName);
        if (categoryType != null) {
            WmsMaterial m = new WmsMaterial();
            m.setId("m-" + id);
            m.setMaterialName(matName);
            m.setCategoryId("c-" + id);
            when(materialService.getById("m-" + id)).thenReturn(m);
            WmsMaterialCategory c = new WmsMaterialCategory();
            c.setId("c-" + id);
            c.setCategoryType(categoryType);
            when(categoryService.getById("c-" + id)).thenReturn(c);
        } else {
            // 口径 10 的「其他」：有物料但分类缺失/无类型
            WmsMaterial m = new WmsMaterial();
            m.setId("m-" + id);
            m.setMaterialName(matName);
            when(materialService.getById("m-" + id)).thenReturn(m);
        }
        return d;
    }

    private org.jeecg.modules.wms.entity.WmsLocation loc(String id) {
        org.jeecg.modules.wms.entity.WmsLocation l = new org.jeecg.modules.wms.entity.WmsLocation();
        l.setId(id);
        l.setLocationCode(id);
        l.setStatus("空闲");
        return l;
    }

    @Test
    void autoAllocateMapsHalfFinishedToStereo() {
        stubAllocateEnv(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE,
                java.util.Collections.singletonList(row("d1", "电路板", WmsConstants.CATEGORY_TYPE_HALF_FINISHED)));
        when(locationService.listFreeByWarehouseType("立库"))
                .thenReturn(java.util.Collections.singletonList(loc("LB-1")));

        org.jeecg.modules.wms.vo.WmsAllocateResultVO vo = service.autoAllocate("ib1");

        assertEquals(1, vo.getAllocatedCount());
        org.mockito.ArgumentCaptor<org.jeecg.modules.wms.entity.WmsInboundDetail> cap =
                org.mockito.ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsInboundDetail.class);
        verify(detailMapper).updateById(cap.capture());
        assertEquals("LB-1", cap.getValue().getLocationId());
        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, service.getById("ib1").getInboundStatus());
    }

    @Test
    void autoAllocateMapsAuxiliaryToLineSide() {
        stubAllocateEnv(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE,
                java.util.Collections.singletonList(row("d1", "原材料", WmsConstants.CATEGORY_TYPE_AUXILIARY)));
        when(locationService.listFreeByWarehouseType("线边柜"))
                .thenReturn(java.util.Collections.singletonList(loc("XB-1")));

        org.jeecg.modules.wms.vo.WmsAllocateResultVO vo = service.autoAllocate("ib1");

        assertEquals(1, vo.getAllocatedCount());
        org.mockito.ArgumentCaptor<org.jeecg.modules.wms.entity.WmsInboundDetail> cap =
                org.mockito.ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsInboundDetail.class);
        verify(detailMapper).updateById(cap.capture());
        assertEquals("XB-1", cap.getValue().getLocationId());
    }

    @Test
    void autoAllocateReturnsWarningsWhenInsufficient() {
        org.jeecg.modules.wms.entity.WmsInboundDetail d1 = row("d1", "电路板", WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        org.jeecg.modules.wms.entity.WmsInboundDetail d2 = row("d2", "电路板", WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        stubAllocateEnv(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, java.util.Arrays.asList(d1, d2));
        when(locationService.listFreeByWarehouseType("立库"))
                .thenReturn(java.util.Collections.singletonList(loc("LB-1")));

        org.jeecg.modules.wms.vo.WmsAllocateResultVO vo = service.autoAllocate("ib1");

        assertEquals(1, vo.getAllocatedCount());
        assertEquals(1, vo.getUnallocated().size());
        assertEquals("库位不足", vo.getUnallocated().get(0).getReason());
        assertTrue(vo.getWarnings().size() > 0);
        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, service.getById("ib1").getInboundStatus());
    }

    @Test
    void autoAllocateSkipsUnknownCategoryType() {
        stubAllocateEnv(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE,
                java.util.Collections.singletonList(row("d1", "神秘物料", null)));

        org.jeecg.modules.wms.vo.WmsAllocateResultVO vo = service.autoAllocate("ib1");

        assertEquals(0, vo.getAllocatedCount());
        assertEquals("该物料类型不支持自动分配", vo.getUnallocated().get(0).getReason());
        verify(detailMapper, never()).updateById(any(org.jeecg.modules.wms.entity.WmsInboundDetail.class));
    }

    @Test
    void autoAllocateRejectsWrongState() {
        stubAllocateEnv(WmsConstants.INBOUND_STATUS_PENDING_INSPECT, java.util.Collections.emptyList());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.autoAllocate("ib1"));
        assertEquals("当前状态不允许自动分配", ex.getMessage());
    }

    // ------------------------------------------------------------ 上架确认（RK-001 step6）

    private org.jeecg.modules.wms.service.IWmsInboundSyncService syncService;
    private org.jeecg.modules.wms.mapper.WmsLocationMapper locationMapper;
    private org.jeecg.modules.wms.service.IWmsWarehouseService warehouseService;
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;

    /** 构造待上架环境：1 行半成品明细、立库库位 LB-1、同步服务 mock。 */
    private void stubPutawayEnv(String orderStatus, java.math.BigDecimal actual) {
        syncService = mock(org.jeecg.modules.wms.service.IWmsInboundSyncService.class);
        locationMapper = mock(org.jeecg.modules.wms.mapper.WmsLocationMapper.class);
        warehouseService = mock(org.jeecg.modules.wms.service.IWmsWarehouseService.class);
        inventoryMapper = mock(org.jeecg.modules.wms.mapper.WmsInventoryMapper.class);
        WmsTestSupport.inject(service, "syncService", syncService);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "warehouseService", warehouseService);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);

        WmsInboundServiceImpl spyService = org.mockito.Mockito.spy(service);
        org.jeecg.modules.wms.entity.WmsInboundOrder order = new org.jeecg.modules.wms.entity.WmsInboundOrder();
        order.setId("ib1");
        order.setInboundStatus(orderStatus);
        doReturn(order).when(spyService).getById("ib1");
        doReturn(true).when(spyService).updateById(any(org.jeecg.modules.wms.entity.WmsInboundOrder.class));

        org.jeecg.modules.wms.entity.WmsInboundDetail d = row("d1", "电路板", WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        d.setInboundId("ib1");
        d.setQuantity(new java.math.BigDecimal("10"));
        d.setBatchNo("B001");
        when(detailMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(d));
        putawayRow1 = d;

        org.jeecg.modules.wms.entity.WmsLocation lb = loc("LB-1");
        lb.setWarehouseId("wh1");
        doReturn(lb).when(locationMapper).selectById("LB-1");
        when(warehouseService.getById("wh1")).thenReturn(wh("wh1", "立库"));
        when(locationMapper.update(any(), any())).thenReturn(1);

        org.jeecg.modules.wms.dto.WmsPutawayDTO dto = new org.jeecg.modules.wms.dto.WmsPutawayDTO();
        org.jeecg.modules.wms.dto.WmsPutawayDTO.Item item = new org.jeecg.modules.wms.dto.WmsPutawayDTO.Item();
        item.setDetailId("d1");
        item.setActualQuantity(actual);
        item.setLocationId("LB-1");
        dto.setDetails(java.util.Collections.singletonList(item));

        service = spyService;
        putawayDto = dto;
    }

    private org.jeecg.modules.wms.dto.WmsPutawayDTO putawayDto;
    private org.jeecg.modules.wms.entity.WmsInboundDetail putawayRow1;

    private org.jeecg.modules.wms.entity.WmsWarehouse wh(String id, String type) {
        org.jeecg.modules.wms.entity.WmsWarehouse w = new org.jeecg.modules.wms.entity.WmsWarehouse();
        w.setId(id);
        w.setWarehouseType(type);
        return w;
    }

    @Test
    void putawayWritesInventoryAndAudits() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, new java.math.BigDecimal("10"));
        when(syncService.syncInbound(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new org.jeecg.modules.wms.sync.WmsOrderSyncAttempt(true, null, 0));

        service.putaway("ib1", putawayDto);

        verify(detailMapper).updateById(any(org.jeecg.modules.wms.entity.WmsInboundDetail.class));
        org.mockito.ArgumentCaptor<org.jeecg.modules.wms.entity.WmsInventory> invCap =
                org.mockito.ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsInventory.class);
        org.mockito.Mockito.verify(inventoryMapper).upsertInventory(invCap.capture());
        assertEquals("m-d1", invCap.getValue().getMaterialId());
        assertEquals(new java.math.BigDecimal("10"), invCap.getValue().getQuantity());
        assertEquals(WmsConstants.INBOUND_STATUS_AUDITED, service.getById("ib1").getInboundStatus());
        assertEquals("system", service.getById("ib1").getAuditBy());
    }

    @Test
    void putawaySkipsZeroActualRows() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, new java.math.BigDecimal("10"));
        // 追加一行实收 0 的 d2（有物料、半成品）
        org.jeecg.modules.wms.entity.WmsInboundDetail d2 = row("d2", "电路板2", WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        d2.setInboundId("ib1");
        d2.setQuantity(new java.math.BigDecimal("5"));
        d2.setBatchNo("B002");
        when(detailMapper.selectList(any()))
                .thenReturn(java.util.Arrays.asList(putawayRow1, d2));
        org.jeecg.modules.wms.dto.WmsPutawayDTO.Item zeroItem = new org.jeecg.modules.wms.dto.WmsPutawayDTO.Item();
        zeroItem.setDetailId("d2");
        zeroItem.setActualQuantity(java.math.BigDecimal.ZERO);
        java.util.List<org.jeecg.modules.wms.dto.WmsPutawayDTO.Item> items = new java.util.ArrayList<>(putawayDto.getDetails());
        items.add(zeroItem);
        putawayDto.setDetails(items);
        when(syncService.syncInbound(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new org.jeecg.modules.wms.sync.WmsOrderSyncAttempt(true, null, 0));

        service.putaway("ib1", putawayDto);

        org.mockito.Mockito.verify(inventoryMapper, org.mockito.Mockito.times(1))
                .upsertInventory(any(org.jeecg.modules.wms.entity.WmsInventory.class));
        org.mockito.Mockito.verify(locationMapper, org.mockito.Mockito.times(1))
                .update(any(), any());
        org.mockito.ArgumentCaptor<org.jeecg.modules.wms.entity.WmsInboundDetail> cap =
                org.mockito.ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsInboundDetail.class);
        org.mockito.Mockito.verify(detailMapper, org.mockito.Mockito.times(2)).updateById(cap.capture());
        assertEquals(java.math.BigDecimal.ZERO, cap.getAllValues().get(1).getActualQuantity());
        assertEquals(WmsConstants.INBOUND_STATUS_AUDITED, service.getById("ib1").getInboundStatus());
    }

    @Test
    void putawayRejectsAllZeroRows() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, java.math.BigDecimal.ZERO);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.putaway("ib1", putawayDto));
        assertEquals("整单实收不能全为 0", ex.getMessage());
    }

    @Test
    void putawayRejectsNonPendingState() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_AUDITED, new java.math.BigDecimal("10"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.putaway("ib1", putawayDto));
        assertEquals("当前状态不允许上架", ex.getMessage());
    }

    @Test
    void putawayFailsWhenLocationOccupiedConcurrently() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, new java.math.BigDecimal("10"));
        when(locationMapper.update(any(), any())).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.putaway("ib1", putawayDto));
        assertEquals("库位已被占用，请重新分配", ex.getMessage());
    }

    @Test
    void putawayRejectsWarehouseTypeMismatch() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, new java.math.BigDecimal("10"));
        // 库位挂在敏捷仓（成品）下，与半成品物料不匹配
        org.jeecg.modules.wms.entity.WmsLocation wrong = loc("LB-1");
        wrong.setWarehouseId("wh1");
        doReturn(wrong).when(locationMapper).selectById("LB-1");
        when(warehouseService.getById("wh1")).thenReturn(wh("wh1", "敏捷仓"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.putaway("ib1", putawayDto));
        assertEquals("库位类型与物料不匹配", ex.getMessage());
    }

    @Test
    void syncFailureDoesNotRollbackAudit() {
        stubPutawayEnv(WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY, new java.math.BigDecimal("10"));
        when(syncService.syncInbound(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new RuntimeException("ERP 连接失败"));

        service.putaway("ib1", putawayDto);

        assertEquals(WmsConstants.INBOUND_STATUS_AUDITED, service.getById("ib1").getInboundStatus());
        assertEquals(WmsConstants.SYNC_STATUS_FAILED, service.getById("ib1").getSyncStatus());
        assertTrue(service.getById("ib1").getLastSyncError().contains("ERP 连接失败"));
    }

    // ------------------------------------------------------------ 作废（口径 7）

    @Test
    void voidSetsTerminalStateFromPendingInspect() {
        inspectMapperStub();
        setOrderStatus(WmsConstants.INBOUND_STATUS_PENDING_INSPECT);

        service.voidOrder("ib1", "物料录错");

        assertEquals(WmsConstants.INBOUND_STATUS_VOIDED, service.getById("ib1").getInboundStatus());
        assertTrue(service.getById("ib1").getRejectReason().contains("物料录错"));
    }

    @Test
    void voidRejectsAuditedOrder() {
        inspectMapperStub();
        setOrderStatus(WmsConstants.INBOUND_STATUS_AUDITED);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.voidOrder("ib1", "试图作废已入库单"));
        assertEquals("当前状态不可作废", ex.getMessage());
    }

    @Test
    void voidRejectsBlankReason() {
        inspectMapperStub();
        setOrderStatus(WmsConstants.INBOUND_STATUS_PENDING_INSPECT);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.voidOrder("ib1", "  "));
        assertEquals("作废原因不能为空", ex.getMessage());
    }

    /** 允许作废的三个状态；其余状态必须拒绝。 */
    @Test
    void voidAllowsAllThreePreStockStates() {
        for (String status : new String[] {WmsConstants.INBOUND_STATUS_PENDING_INSPECT,
                WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, WmsConstants.INBOUND_STATUS_PENDING_PUTAWAY}) {
            inspectMapperStub();
            setOrderStatus(status);
            service.voidOrder("ib1", "测试");
            assertEquals(WmsConstants.INBOUND_STATUS_VOIDED, service.getById("ib1").getInboundStatus(), status);
        }
    }

    private org.jeecg.modules.wms.entity.WmsInboundOrder currentOrder;

    private void setOrderStatus(String status) {
        currentOrder = new org.jeecg.modules.wms.entity.WmsInboundOrder();
        currentOrder.setId("ib1");
        currentOrder.setOrderNo("SL001");
        currentOrder.setInboundStatus(status);
        WmsInboundServiceImpl spyService = org.mockito.Mockito.spy(new WmsInboundServiceImpl());
        copyInjected(spyService);
        doReturn(currentOrder).when(spyService).getById("ib1");
        doReturn(true).when(spyService).updateById(any(org.jeecg.modules.wms.entity.WmsInboundOrder.class));
        service = spyService;
    }

    private void inspectMapperStub() {
        // 仅确保 baseMapper 等依赖存在（setUp 已注入）
    }

    private void copyInjected(WmsInboundServiceImpl target) {
        WmsTestSupport.inject(target, "baseMapper", mock(org.jeecg.modules.wms.mapper.WmsInboundOrderMapper.class));
        WmsTestSupport.inject(target, "orderService", orderService);
        WmsTestSupport.inject(target, "materialService", materialService);
        WmsTestSupport.inject(target, "categoryService", categoryService);
        WmsTestSupport.inject(target, "detailMapper", detailMapper);
        WmsTestSupport.inject(target, "inspectionTaskMapper", inspectionTaskMapper);
    }
}
