package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsPutawayDTO;
import org.jeecg.modules.wms.dto.WmsRawInboundDTO;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.entity.WmsCustomer;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.service.IWmsCustomerService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeeConsignedInboundWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReceiveWriter;
import org.jeecg.modules.wms.sync.IWmsKingdeeRawOrderReader;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: RK-001 外购/受托原材料到货登记与段差棒直入（V2.4 spec §3.2）单测。
 *
 * <p>锁五件事：
 * <ol>
 *   <li>来源 × 处理四组合：只有 DIRECT 产生库存，且 CONSIGNED 写受托行（必须带受托方）；</li>
 *   <li>V2.6 起 CONVERT（改刀料）与 DIRECT（段差棒）同口径：待分配→上架→库存→回写；</li>
 *   <li>金蝶开关关闭 / formId 未配置必须显式拒绝，且在写库存之前；</li>
 *   <li>回写成功回存 ERP 单号与同步时间；</li>
 *   <li>Submit 失败不回滚库存，只落同步失败。</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsRawInboundServiceImplTest {

    private static final String OPERATOR = "tester";
    private static final String ERP_ORDER_NO = "PO001";

    private WmsInboundOrderMapper orderMapper;
    private WmsInboundDetailMapper detailMapper;
    private IWmsMaterialService materialService;
    private IWmsMaterialCategoryService categoryService;
    private IWmsSupplierService supplierService;
    private IWmsCustomerService customerService;
    private WmsLocationMapper locationMapper;
    private IWmsWarehouseService warehouseService;
    private WmsInventoryMapper inventoryMapper;
    private IWmsKingdeeRawOrderReader reader;
    private IWmsKingdeePurchaseReceiveWriter purchaseReceiveWriter;
    private IWmsKingdeeConsignedInboundWriter consignedWriter;
    private WmsKingdeeFormClient formClient;
    private IWmsSystemConfigService configService;

    private WmsRawInboundServiceImpl service;
    private WmsRawInboundServiceImpl spy;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsInboundOrderMapper.class);
        detailMapper = mock(WmsInboundDetailMapper.class);
        materialService = mock(IWmsMaterialService.class);
        categoryService = mock(IWmsMaterialCategoryService.class);
        supplierService = mock(IWmsSupplierService.class);
        customerService = mock(IWmsCustomerService.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseService = mock(IWmsWarehouseService.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        reader = mock(IWmsKingdeeRawOrderReader.class);
        purchaseReceiveWriter = mock(IWmsKingdeePurchaseReceiveWriter.class);
        consignedWriter = mock(IWmsKingdeeConsignedInboundWriter.class);
        formClient = mock(WmsKingdeeFormClient.class);
        configService = mock(IWmsSystemConfigService.class);

        service = new WmsRawInboundServiceImpl(detailMapper, materialService, categoryService,
                supplierService, customerService, locationMapper, warehouseService, inventoryMapper,
                reader, purchaseReceiveWriter, consignedWriter, formClient, configService);
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        spy = Mockito.spy(service);
        doReturn("SL202611040001").when(spy).nextOrderNo("SL");

        when(orderMapper.insert(any(WmsInboundOrder.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, WmsInboundOrder.class).setId("in1");
            return 1;
        });
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(orderMapper.updateById(any(WmsInboundOrder.class))).thenReturn(1);
        when(detailMapper.updateById(any(WmsInboundDetail.class))).thenReturn(1);
        when(locationMapper.update(any(), any())).thenReturn(1);
        when(inventoryMapper.upsertInventory(any(WmsInventory.class))).thenReturn(1);
        // V2.4 Task 9：外购必须带供应商，helper dto() 统一填 sup1
        when(supplierService.getById("sup1")).thenReturn(supplier());
        when(customerService.getById("sup1")).thenReturn(customer());
    }

    // ------------------------------------------------------------------ 到货登记

    @Test
    void receiveDirectPurchaseCreatesPendingAllocateOrder() {
        stubErpOrder(false);
        stubRawMaterial();

        WmsInboundOrder order = spy.receive(dto(WmsConstants.INBOUND_SOURCE_PURCHASE,
                WmsConstants.INBOUND_HANDLING_DIRECT), OPERATOR);

        assertEquals("SL202611040001", order.getOrderNo());
        assertEquals(WmsConstants.INBOUND_SOURCE_PURCHASE, order.getSourceType());
        assertEquals(WmsConstants.INBOUND_TYPE_RAW_PURCHASE, order.getInboundType());
        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, order.getInboundStatus());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertEquals(ERP_ORDER_NO, order.getPurchaseOrderNo());
        verify(reader).queryPurchaseOrder(ERP_ORDER_NO);

        ArgumentCaptor<WmsInboundDetail> detail = ArgumentCaptor.forClass(WmsInboundDetail.class);
        verify(detailMapper).insert(detail.capture());
        assertEquals("in1", detail.getValue().getInboundId());
        assertEquals("M001", detail.getValue().getMaterialCode());
        assertEquals("B20261104", detail.getValue().getBatchNo());
        assertEquals(0, new BigDecimal("10").compareTo(detail.getValue().getQuantity()));
    }

    @Test
    void receivePurchaseWithoutSupplierIsRejected() {
        WmsRawInboundDTO dto = dto(WmsConstants.INBOUND_SOURCE_PURCHASE,
                WmsConstants.INBOUND_HANDLING_DIRECT);
        dto.setSupplierId("  ");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.receive(dto, OPERATOR));

        assertTrue(e.getMessage().contains("供应商"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
        // 缺供应商在 ERP 查询之前就拒绝，不能白调金蝶
        verify(reader, never()).queryPurchaseOrder(anyString());
    }

    @Test
    void receiveConsignedWithoutConsignorIsRejected() {
        WmsRawInboundDTO dto = dto(WmsConstants.INBOUND_SOURCE_CONSIGNED,
                WmsConstants.INBOUND_HANDLING_DIRECT);
        dto.setConsignorId("  ");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.receive(dto, OPERATOR));

        assertTrue(e.getMessage().contains("受托方"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
        verify(reader, never()).queryConsignedOrder(anyString());
    }

    @Test
    void receiveConsignedDirectBindsConsignorAsCustomer() {
        stubErpOrder(true);
        stubRawMaterial();
        when(customerService.getById("sup1")).thenReturn(customer());

        WmsRawInboundDTO dto = dto(WmsConstants.INBOUND_SOURCE_CONSIGNED,
                WmsConstants.INBOUND_HANDLING_DIRECT);
        dto.setConsignorId("sup1");
        WmsInboundOrder order = spy.receive(dto, OPERATOR);

        assertEquals(WmsConstants.INBOUND_SOURCE_CONSIGNED, order.getSourceType());
        assertEquals(WmsConstants.INBOUND_TYPE_RAW_CONSIGNED, order.getInboundType());
        // 受托方写 consignor_*（客户档案），supplier_* 只给外购
        assertEquals("sup1", order.getConsignorId());
        assertEquals("CUST001", order.getConsignorCode());
        assertNull(order.getSupplierId());
        assertNull(order.getSupplierCode());
        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, order.getInboundStatus());
        verify(reader).queryConsignedOrder(ERP_ORDER_NO);
    }

    /**
     * V2.6（2026-11-10 客户变更）：改刀料与段差棒同口径——登记为「待分配」，
     * 可上架入库存并回写采购收料单；不再有「登记即终态」分支。
     */
    @Test
    void receiveConvertRegistersAsPendingAllocateAndAllowsPutaway() {
        stubErpOrder(false);
        stubRawMaterial();

        WmsInboundOrder order = spy.receive(dto(WmsConstants.INBOUND_SOURCE_PURCHASE,
                WmsConstants.INBOUND_HANDLING_CONVERT), OPERATOR);

        assertEquals(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE, order.getInboundStatus());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertNull(order.getAuditBy(), "改刀料不再登记即终态，不应写审核人");
        assertNull(order.getAuditTime());

        // 与段差棒一致：待分配单据可上架，上架后入库存并回写采购收料单
        stubPutawayReady(order, WmsConstants.INBOUND_SOURCE_PURCHASE);
        when(purchaseReceiveWriter.write(order))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "CGRK002", "erpId2"));
        when(formClient.submit(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
        when(orderMapper.selectById("in1")).thenReturn(order);

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result =
                spy.putawayRaw("in1", putawayDto(), OPERATOR);

        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, result.getSyncStatus());
        assertEquals("CGRK002", result.getErpBillNo());
        verify(inventoryMapper).upsertInventory(any(WmsInventory.class));
    }

    /** I3：同一 ERP 订单重复登记 → 拒绝并带上已存在单号，不落新单也不查金蝶。 */
    @Test
    void receiveRejectsDuplicateErpOrder() {
        WmsInboundOrder existing = new WmsInboundOrder()
                .setOrderNo("SL202611040009")
                .setSourceType(WmsConstants.INBOUND_SOURCE_PURCHASE)
                .setPurchaseOrderNo(ERP_ORDER_NO);
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.receive(dto(WmsConstants.INBOUND_SOURCE_PURCHASE,
                        WmsConstants.INBOUND_HANDLING_DIRECT), OPERATOR));

        assertTrue(e.getMessage().contains("已登记"), e.getMessage());
        assertTrue(e.getMessage().contains("SL202611040009"), e.getMessage());
        // 判重在 ERP 查询与落单之前，不能白调金蝶、更不能落第二张单
        verify(reader, never()).queryPurchaseOrder(anyString());
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));

        // 判重条件必须包含「来源类型 + ERP 订单号 + 非作废」
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsInboundOrder>>
                captor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(orderMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsInboundOrder> qw =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsInboundOrder>) captor.getValue();
        String sql = qw.getSqlSegment();
        assertTrue(sql.contains("source_type") && sql.contains("purchase_order_no")
                && sql.contains("inbound_status"), sql);
        assertTrue(qw.getParamNameValuePairs().values().contains(WmsConstants.INBOUND_STATUS_VOIDED),
                "必须排除作废单，否则作废后无法重新登记");
    }

    @Test
    void receiveRejectsUnknownSourceOrHandlingType() {
        WmsRawInboundDTO badSource = dto("OTHER", WmsConstants.INBOUND_HANDLING_DIRECT);
        assertThrows(RuntimeException.class, () -> spy.receive(badSource, OPERATOR));

        WmsRawInboundDTO badHandling = dto(WmsConstants.INBOUND_SOURCE_PURCHASE, "OTHER");
        assertThrows(RuntimeException.class, () -> spy.receive(badHandling, OPERATOR));
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
    }

    @Test
    void receiveRejectsNonRawMaterial() {
        when(reader.isEnabled()).thenReturn(true);
        when(reader.queryPurchaseOrder(ERP_ORDER_NO)).thenReturn(erpOrderResult());
        WmsMaterial material = new WmsMaterial();
        material.setId("mat1");
        material.setMaterialCode("M001");
        material.setMaterialName("半成品A");
        material.setCategoryId("cat1");
        when(materialService.getById("mat1")).thenReturn(material);
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId("cat1");
        category.setCategoryType(WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        when(categoryService.getById("cat1")).thenReturn(category);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.receive(
                dto(WmsConstants.INBOUND_SOURCE_PURCHASE, WmsConstants.INBOUND_HANDLING_DIRECT), OPERATOR));

        assertTrue(e.getMessage().contains("不是原材料"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
    }

    /**
     * 辅料要给「不在 WMS 范围」的专属文案，而不是笼统的「不是原材料」。
     *
     * <p>2026-11-05 用户口径：辅料本来就不在 WMS 范围、不入库。它该去线边柜，而线边柜 XB01
     * 已停用——用户看到「不是原材料」会以为换个物料就行，必须直接告诉他这是范围问题。
     */
    @Test
    void receiveRejectsAuxiliaryMaterialWithScopeMessage() {
        when(reader.isEnabled()).thenReturn(true);
        when(reader.queryPurchaseOrder(ERP_ORDER_NO)).thenReturn(erpOrderResult());
        WmsMaterial material = new WmsMaterial();
        material.setId("mat1");
        material.setMaterialCode("M001");
        material.setMaterialName("螺丝");
        material.setCategoryId("cat1");
        when(materialService.getById("mat1")).thenReturn(material);
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId("cat1");
        category.setCategoryType(WmsConstants.CATEGORY_TYPE_AUXILIARY);
        when(categoryService.getById("cat1")).thenReturn(category);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.receive(
                dto(WmsConstants.INBOUND_SOURCE_PURCHASE, WmsConstants.INBOUND_HANDLING_DIRECT), OPERATOR));

        assertTrue(e.getMessage().contains("属于辅料"), e.getMessage());
        assertTrue(e.getMessage().contains("不在 WMS 范围内"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsInboundOrder.class));
    }

    // ------------------------------------------------------------------ 段差棒直入上架

    @Test
    void putawayDirectPurchaseWritesOwnedInventoryAndStoresErpNumber() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_PURCHASE, null);
        stubPutawayReady(order, WmsConstants.INBOUND_SOURCE_PURCHASE);
        when(purchaseReceiveWriter.write(order))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "CGRK001", "erpId1"));
        when(formClient.submit(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result =
                spy.putawayRaw("in1", putawayDto(), OPERATOR);

        // C1：上架必须把同步结果返回给前端，否则用户永远看不到回写失败
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, result.getSyncStatus());
        assertEquals("CGRK001", result.getErpBillNo());
        assertNull(result.getLastSyncError());

        ArgumentCaptor<WmsInventory> inv = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(inv.capture());
        assertEquals(WmsConstants.OWNERSHIP_OWNED, inv.getValue().getOwnershipType());
        assertEquals("", inv.getValue().getConsignorId());
        assertEquals(0, new BigDecimal("10").compareTo(inv.getValue().getQuantity()));
        assertEquals("loc1", inv.getValue().getLocationId());
        assertNotNull(inv.getValue().getId(), "自建 id，自定义 upsert 不会生成主键");

        verify(formClient).submit("PUR_IN", List.of("CGRK001"));
        WmsInboundOrder sync = captureSyncUpdate();
        assertEquals("CGRK001", sync.getErpBillNo());
        assertNotNull(sync.getErpSyncTime());
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, sync.getSyncStatus());
        assertNull(sync.getLastSyncError());
        verify(consignedWriter, never()).write(any());
    }

    @Test
    void putawayDirectConsignedWritesConsignedInventoryWithConsignor() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_CONSIGNED, "sup1");
        stubPutawayReady(order, WmsConstants.INBOUND_SOURCE_CONSIGNED);
        when(consignedWriter.write(order))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "STD001", "erpId2"));

        spy.putawayRaw("in1", putawayDto(), OPERATOR);

        ArgumentCaptor<WmsInventory> inv = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(inv.capture());
        assertEquals(WmsConstants.OWNERSHIP_CONSIGNED, inv.getValue().getOwnershipType());
        assertEquals("sup1", inv.getValue().getConsignorId());

        // 受托入库 Writer 内部已完成 Push→Save→Submit，服务层不得再提交一次
        verify(formClient, never()).submit(anyString(), any());
        verify(purchaseReceiveWriter, never()).write(any());
        assertEquals("STD001", captureSyncUpdate().getErpBillNo());
    }

    @Test
    void putawayRejectedWhenKingdeeSwitchIsOff() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_PURCHASE, null);
        when(orderMapper.selectById("in1")).thenReturn(order);
        when(purchaseReceiveWriter.isEnabled()).thenReturn(false);
        when(configService.getString(eq(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), anyString()))
                .thenReturn("PUR_IN");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.putawayRaw("in1", putawayDto(), OPERATOR));

        assertEquals(WmsRawInboundServiceImpl.KD_NOT_READY, e.getMessage());
        verify(inventoryMapper, never()).upsertInventory(any(WmsInventory.class));
        verify(locationMapper, never()).update(any(), any());
        verify(orderMapper, never()).update(any(), any());
        verify(purchaseReceiveWriter, never()).write(any());
    }

    @Test
    void putawayRejectedWhenFormIdIsBlank() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_CONSIGNED, "sup1");
        when(orderMapper.selectById("in1")).thenReturn(order);
        when(consignedWriter.isEnabled()).thenReturn(true);
        when(configService.getString(eq(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND.getKey()), anyString()))
                .thenReturn("   ");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.putawayRaw("in1", putawayDto(), OPERATOR));

        assertEquals(WmsRawInboundServiceImpl.KD_NOT_READY, e.getMessage());
        verify(inventoryMapper, never()).upsertInventory(any(WmsInventory.class));
        verify(consignedWriter, never()).write(any());
    }

    @Test
    void submitFailureKeepsInventoryButRecordsSyncFailure() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_PURCHASE, null);
        stubPutawayReady(order, WmsConstants.INBOUND_SOURCE_PURCHASE);
        when(purchaseReceiveWriter.write(order))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "CGRK002", "erpId3"));
        when(formClient.submit(anyString(), any()))
                .thenReturn(WmsKingdeeResult.fail("单据未配置工作流"));

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result =
                spy.putawayRaw("in1", putawayDto(), OPERATOR);

        // 库存照常入账（不因 Submit 失败回滚），但结果对象必须带出失败
        verify(inventoryMapper).upsertInventory(any(WmsInventory.class));
        assertEquals(WmsConstants.SYNC_STATUS_FAILED, result.getSyncStatus());
        assertTrue(result.getLastSyncError().contains("单据未配置工作流"), result.getLastSyncError());
        assertEquals("CGRK002", result.getErpBillNo());

        WmsInboundOrder sync = captureSyncUpdate();
        assertEquals(WmsConstants.SYNC_STATUS_FAILED, sync.getSyncStatus());
        assertTrue(sync.getLastSyncError().contains("单据未配置工作流"), sync.getLastSyncError());
        // Save 已建出的 ERP 草稿必须记下单号，否则重试会重复建单
        assertEquals("CGRK002", sync.getErpBillNo());
        assertNull(sync.getErpSyncTime());
    }

    // ------------------------------------------------------------------ 重试回写（C1 补偿入口）

    /** 幂等：已经同步成功 → 直接返回，不重复开单、不重复提交。 */
    @Test
    void resyncAlreadySuccess_isIdempotent() {
        auditedOrder(WmsConstants.SYNC_STATUS_SUCCESS, "CGRK001");

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result = spy.resync("in1", OPERATOR);

        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, result.getSyncStatus());
        assertEquals("CGRK001", result.getErpBillNo());
        verify(purchaseReceiveWriter, never()).write(any());
        verify(formClient, never()).submit(anyString(), any());
        verify(orderMapper, never()).updateById(any(WmsInboundOrder.class));
    }

    /** 已有 ERP 单号（上次 Save 成功、Submit 失败）→ 只补提交，绝不重复 Save。 */
    @Test
    void resyncWithExistingBill_onlySubmits() {
        auditedOrder(WmsConstants.SYNC_STATUS_FAILED, "CGRK002");
        when(purchaseReceiveWriter.isEnabled()).thenReturn(true);
        when(configService.getString(eq(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), anyString()))
                .thenReturn("PUR_IN");
        when(formClient.submit("PUR_IN", List.of("CGRK002")))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result = spy.resync("in1", OPERATOR);

        verify(purchaseReceiveWriter, never()).write(any());
        verify(formClient).submit("PUR_IN", List.of("CGRK002"));
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, result.getSyncStatus());
        assertEquals("CGRK002", result.getErpBillNo());

        WmsInboundOrder patch = captureSyncUpdate();
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, patch.getSyncStatus());
        assertNotNull(patch.getErpSyncTime());
    }

    /** 没有 ERP 单号（上次 Save 就失败）→ 走完整 Save→Submit→回存。 */
    @Test
    void resyncWithoutBill_doesFullSaveAndSubmit() {
        WmsInboundOrder order = auditedOrder(WmsConstants.SYNC_STATUS_FAILED, null);
        when(purchaseReceiveWriter.isEnabled()).thenReturn(true);
        when(configService.getString(eq(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), anyString()))
                .thenReturn("PUR_IN");
        when(purchaseReceiveWriter.write(order)).thenReturn(WmsKingdeeResult.ok("{}", null, "CGRK003", "id3"));
        when(formClient.submit("PUR_IN", List.of("CGRK003")))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        org.jeecg.modules.wms.vo.WmsRawPutawayResultVO result = spy.resync("in1", OPERATOR);

        verify(purchaseReceiveWriter).write(order);
        verify(formClient).submit("PUR_IN", List.of("CGRK003"));
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, result.getSyncStatus());
        assertEquals("CGRK003", result.getErpBillNo());
    }

    /** 非「已审核」单据不能重试回写（上架前没有库存，回写会做成无货的入库单）。 */
    @Test
    void resyncRequiresAuditedStatus() {
        WmsInboundOrder order = pendingDirectOrder(WmsConstants.INBOUND_SOURCE_PURCHASE, null);
        order.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.resync("in1", OPERATOR));

        assertTrue(e.getMessage().contains("仅已审核"), e.getMessage());
        verify(formClient, never()).submit(anyString(), any());
    }

    /** V2.6 前的历史大改小单据登记即终态、原材料不入库存，不存在可回写的入库单。 */
    @Test
    void resyncRejectsLegacyConvertOrder() {
        auditedOrder(WmsConstants.SYNC_STATUS_NOT_REQUIRED, null);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.resync("in1", OPERATOR));

        assertTrue(e.getMessage().contains("无需同步"), e.getMessage());
        verify(purchaseReceiveWriter, never()).write(any());
    }

    /** 旧通道单据（source_type 为空）不属于本端点。 */
    @Test
    void resyncRejectsNonRawOrder() {
        WmsInboundOrder legacy = new WmsInboundOrder()
                .setOrderNo("RK001")
                .setInboundStatus(WmsConstants.INBOUND_STATUS_AUDITED)
                .setSyncStatus(WmsConstants.SYNC_STATUS_FAILED);
        legacy.setId("in1");
        when(orderMapper.selectById("in1")).thenReturn(legacy);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.resync("in1", OPERATOR));

        assertTrue(e.getMessage().contains("不是外购/受托"), e.getMessage());
    }

    // ------------------------------------------------------------------ 辅助

    /** 一张「已审核」的直入单（补偿入口只接受这个状态）。 */
    private WmsInboundOrder auditedOrder(String syncStatus, String erpBillNo) {
        WmsInboundOrder order = new WmsInboundOrder()
                .setOrderNo("SL202611040001")
                .setSourceType(WmsConstants.INBOUND_SOURCE_PURCHASE)
                .setInboundType(WmsConstants.INBOUND_TYPE_RAW_PURCHASE)
                .setInboundStatus(WmsConstants.INBOUND_STATUS_AUDITED)
                .setSyncStatus(syncStatus)
                .setErpBillNo(erpBillNo);
        order.setId("in1");
        when(orderMapper.selectById("in1")).thenReturn(order);
        return order;
    }

    /** 构造一张「待分配」的直入单（省去走 receive）。 */
    private WmsInboundOrder pendingDirectOrder(String sourceType, String supplierId) {
        WmsInboundOrder order = new WmsInboundOrder()
                .setOrderNo("SL202611040001")
                .setSourceType(sourceType)
                .setInboundType(WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(sourceType)
                        ? WmsConstants.INBOUND_TYPE_RAW_CONSIGNED : WmsConstants.INBOUND_TYPE_RAW_PURCHASE)
                .setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_ALLOCATE)
                .setSyncStatus(WmsConstants.SYNC_STATUS_PENDING);
        if (WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(sourceType)) {
            order.setConsignorId(supplierId);
        } else {
            order.setSupplierId(supplierId);
        }
        order.setCreateTime(new Date());
        order.setId("in1");
        when(orderMapper.selectById("in1")).thenReturn(order);
        return order;
    }

    /** 上架所需的物料/库位/库区与金蝶配置。 */
    private void stubPutawayReady(WmsInboundOrder order, String sourceType) {
        boolean consigned = WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(sourceType);
        if (consigned) {
            when(consignedWriter.isEnabled()).thenReturn(true);
            when(configService.getString(eq(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND.getKey()), anyString()))
                    .thenReturn("STD_IN");
        } else {
            when(purchaseReceiveWriter.isEnabled()).thenReturn(true);
            when(configService.getString(eq(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), anyString()))
                    .thenReturn("PUR_IN");
        }
        WmsInboundDetail detail = detail();
        when(detailMapper.selectList(any())).thenReturn(Collections.singletonList(detail));

        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("A-01-01");
        location.setWarehouseId("wh1");
        location.setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        location.setStocktakeFrozen(WmsConstants.STOCKTAKE_UNFROZEN);
        when(locationMapper.selectById("loc1")).thenReturn(location);

        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId("wh1");
        warehouse.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO);
        when(warehouseService.getById("wh1")).thenReturn(warehouse);

        stubRawMaterial();
    }

    private WmsInboundDetail detail() {
        WmsInboundDetail detail = new WmsInboundDetail()
                .setInboundId("in1")
                .setMaterialId("mat1")
                .setMaterialCode("M001")
                .setMaterialName("段差棒")
                .setBatchNo("B20261104")
                .setQuantity(new BigDecimal("10"));
        detail.setId("d1");
        return detail;
    }

    private WmsPutawayDTO putawayDto() {
        WmsPutawayDTO.Item item = new WmsPutawayDTO.Item();
        item.setDetailId("d1");
        item.setActualQuantity(new BigDecimal("10"));
        item.setLocationId("loc1");
        WmsPutawayDTO dto = new WmsPutawayDTO();
        dto.setDetails(Collections.singletonList(item));
        return dto;
    }

    private WmsInboundOrder captureSyncUpdate() {
        ArgumentCaptor<WmsInboundOrder> captor = ArgumentCaptor.forClass(WmsInboundOrder.class);
        verify(orderMapper).updateById(captor.capture());
        return captor.getValue();
    }

    private void stubErpOrder(boolean consigned) {
        when(reader.isEnabled()).thenReturn(true);
        if (consigned) {
            when(reader.queryConsignedOrder(ERP_ORDER_NO)).thenReturn(erpOrderResult());
        } else {
            when(reader.queryPurchaseOrder(ERP_ORDER_NO)).thenReturn(erpOrderResult());
        }
    }

    private WmsKingdeeResult erpOrderResult() {
        return WmsKingdeeResult.ok("[[]]", List.of(List.of(ERP_ORDER_NO, "M001", "10")), null, null);
    }

    private void stubRawMaterial() {
        WmsMaterial material = new WmsMaterial();
        material.setId("mat1");
        material.setMaterialCode("M001");
        material.setMaterialName("段差棒");
        material.setCategoryId("cat1");
        when(materialService.getById("mat1")).thenReturn(material);
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId("cat1");
        category.setCategoryType(WmsConstants.CATEGORY_TYPE_RAW);
        when(categoryService.getById("cat1")).thenReturn(category);
    }

    private WmsSupplier supplier() {
        WmsSupplier supplier = new WmsSupplier();
        supplier.setId("sup1");
        supplier.setSupplierCode("SUP001");
        supplier.setSupplierName("供应商一");
        return supplier;
    }

    /** 受托方＝客户档案（V2.4：受托方与金蝶保持一致，不再复用供应商）。 */
    private WmsCustomer customer() {
        WmsCustomer customer = new WmsCustomer();
        customer.setId("sup1");
        customer.setCustomerCode("CUST001");
        customer.setCustomerName("受托客户一");
        return customer;
    }

    private WmsRawInboundDTO dto(String sourceType, String handlingType) {
        WmsRawInboundDTO dto = new WmsRawInboundDTO();
        dto.setSourceType(sourceType);
        dto.setHandlingType(handlingType);
        dto.setErpOrderNo(ERP_ORDER_NO);
        // 外购供应商必填（V2.4 Task 9）；受托场景由 service 忽略该字段
        dto.setSupplierId("sup1");
        WmsRawInboundDTO.Item item = new WmsRawInboundDTO.Item();
        item.setMaterialId("mat1");
        item.setQuantity(new BigDecimal("10"));
        item.setBatchNo("B20261104");
        dto.setDetails(Collections.singletonList(item));
        return dto;
    }
}
