package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDefectInboundDTO;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDefectRecordMapper;
import org.jeecg.modules.wms.mapper.WmsInboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsSupplierService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReturnWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: RK-005 两类不良来源单测：受托 SCRAP 扣受托库存（consignor 匹配）且不足拒绝、
 * 受托 RETURN 不动库存、退货聚合与沿用、供应商反查失败拒绝、生产提交写不良记录。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsDefectSourceTest {

    private WmsInventoryMapper inventoryMapper;
    private WmsProductionInboundMapper productionInboundMapper;
    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsMaterialMapper materialMapper;
    private IWmsAuditLogService auditLogService;
    private WmsDefectRecordMapper defectRecordMapper;
    private WmsInboundOrderMapper inboundOrderMapper;
    private IWmsSupplierService supplierService;
    private IWmsKingdeePurchaseReturnWriter returnWriter;
    private WmsKingdeeFormClient formClient;
    private IWmsSystemConfigService configService;
    private ISysBaseAPI sysBaseAPI;
    private WmsDefectServiceImpl service;

    @BeforeEach
    void setUp() {
        inventoryMapper = mock(WmsInventoryMapper.class);
        productionInboundMapper = mock(WmsProductionInboundMapper.class);
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        auditLogService = mock(IWmsAuditLogService.class);
        defectRecordMapper = mock(WmsDefectRecordMapper.class);
        inboundOrderMapper = mock(WmsInboundOrderMapper.class);
        supplierService = mock(IWmsSupplierService.class);
        returnWriter = mock(IWmsKingdeePurchaseReturnWriter.class);
        formClient = mock(WmsKingdeeFormClient.class);
        configService = mock(IWmsSystemConfigService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsDefectServiceImpl(inventoryMapper, orderMapper, detailMapper,
                locationMapper, warehouseMapper, productionInboundMapper, materialMapper,
                auditLogService, defectRecordMapper, inboundOrderMapper, supplierService,
                returnWriter, formClient, configService, sysBaseAPI);
        // 公共桩：物料、废品仓默认位回退链（defect.warehouse.code 未配置 → 废品仓 + FPC-DEFAULT）
        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setMaterialName("铜带").setUnit("米");
        material.setId("MAT1");
        when(materialMapper.selectById("MAT1")).thenReturn(material);
        when(configService.getString(eq("defect.warehouse.code"), anyString())).thenReturn("");
        when(configService.getString(eq("kingdee.form.purchase-return"), anyString())).thenReturn("PUR_ReturnBill");
        when(warehouseMapper.selectOne(org.mockito.ArgumentMatchers
                .<Wrapper<WmsWarehouse>>any())).thenReturn(scrapWarehouse());
        when(locationMapper.selectOne(org.mockito.ArgumentMatchers
                .<Wrapper<WmsLocation>>any())).thenReturn(scrapLocation());
        // 扣减默认成功（自定义 SQL 的并发保护由实现保证，单测桩返回 1 行）
        when(inventoryMapper.deductQuantity(anyString(), any())).thenReturn(1);
    }

    /** 退货用例公共桩：受托反查链（入库单 + 受托方）。 */
    private void stubConsignorChain() {
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(supplier());
    }

    // ------------------------------------------------------------ 桩数据

    private WmsWarehouse scrapWarehouse() {
        WmsWarehouse w = new WmsWarehouse().setWarehouseType(WmsConstants.WAREHOUSE_TYPE_SCRAP);
        w.setId("W-SCRAP");
        return w;
    }

    private WmsLocation scrapLocation() {
        WmsLocation l = new WmsLocation().setWarehouseId("W-SCRAP");
        l.setId("L-SCRAP");
        return l;
    }

    /** 受托入库单：CONSIGNED + 受托方 S1（受托方 id 即库存行的 consignor_id）。 */
    private WmsInboundOrder consignedOrder() {
        WmsInboundOrder o = new WmsInboundOrder()
                .setOrderNo("SL20261104001")
                .setSourceType(WmsConstants.INBOUND_SOURCE_CONSIGNED)
                .setSupplierId("S1");
        o.setId("IO1");
        return o;
    }

    private WmsSupplier supplier() {
        WmsSupplier s = new WmsSupplier().setSupplierCode("C001").setSupplierName("受托方一");
        s.setId("S1");
        return s;
    }

    /** 受托库存行：CONSIGNED + consignor 可指定，默认受托方 S1。 */
    private WmsInventory consignedRow(String id, String consignorId, String qty) {
        WmsInventory i = new WmsInventory().setMaterialId("MAT1").setBatchNo("B1")
                .setOwnershipType(WmsConstants.OWNERSHIP_CONSIGNED)
                .setConsignorId(consignorId)
                .setQuantity(new BigDecimal(qty)).setLockedQty(BigDecimal.ZERO);
        i.setId(id);
        return i;
    }

    private WmsDefectInboundDTO consignedDto(String disposalType, String qty) {
        return new WmsDefectInboundDTO()
                .setSourceType(WmsConstants.DEFECT_SOURCE_CONSIGNED)
                .setSourceBillNo("SL20261104001")
                .setMaterialId("MAT1")
                .setBatchNo("B1")
                .setQty(new BigDecimal(qty))
                .setReason("来料破损")
                .setDisposalType(disposalType);
    }

    /** 待退货记录：来源单一致、物料批次可指定（聚合用例用不同记录区分）。 */
    private WmsDefectRecord pendingRecord(String id, String materialCode, String batchNo, String qty) {
        WmsDefectRecord r = new WmsDefectRecord()
                .setSourceType(WmsConstants.DEFECT_SOURCE_CONSIGNED)
                .setSourceBillNo("SL20261104001")
                .setMaterialCode(materialCode)
                .setBatchNo(batchNo)
                .setQty(new BigDecimal(qty))
                .setDisposalType(WmsConstants.DEFECT_DISPOSAL_RETURN)
                .setStatus(WmsConstants.DEFECT_STATUS_PENDING);
        r.setId(id);
        r.setCreateTime(new Date());
        return r;
    }

    private WmsKingdeeResult okResult(String billNo) {
        return WmsKingdeeResult.ok("{\"Number\":\"" + billNo + "\"}", null, billNo, "100");
    }

    // ------------------------------------------------------------ 受托 SCRAP / RETURN 登记

    /**
     * 受托 SCRAP：扣减查询必须按 物料+批次+CONSIGNED+受托方 过滤（mock 不过滤行，
     * consignor 匹配只能断言查询条件），废品仓落账保留 CONSIGNED + consignor（不转自有）。
     */
    @Test
    void consignedScrap_deductsConsignedInventory_andKeepsConsignedOwnership() {
        stubConsignorChain();
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                consignedRow("IV-A", "S1", "5")));

        service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_SCRAP, "4"), "tester");

        // 1) 扣减查询必须带受托方/所有权/批次条件（DB 侧才不会扣到别人的货）
        ArgumentCaptor<Wrapper<WmsInventory>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(inventoryMapper).selectList(wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("material_id") && sql.contains("batch_no")
                && sql.contains("ownership_type") && sql.contains("consignor_id"), sql);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsInventory> qw =
                (com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WmsInventory>) wrapperCaptor.getValue();
        java.util.Collection<Object> params = qw.getParamNameValuePairs().values();
        assertTrue(params.contains("S1") && params.contains("B1")
                && params.contains("MAT1") && params.contains("CONSIGNED"), params.toString());

        // 2) 只扣返回的受托行
        verify(inventoryMapper).deductQuantity("IV-A", new BigDecimal("4"));
        // 3) 废品仓落账保留 CONSIGNED + consignor
        ArgumentCaptor<WmsInventory> invCaptor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(invCaptor.capture());
        assertEquals(WmsConstants.OWNERSHIP_CONSIGNED, invCaptor.getValue().getOwnershipType());
        assertEquals("S1", invCaptor.getValue().getConsignorId());
        assertEquals("L-SCRAP", invCaptor.getValue().getLocationId());
        assertEquals(0, invCaptor.getValue().getQuantity().compareTo(new BigDecimal("4")));
        ArgumentCaptor<WmsDefectRecord> recCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper).insert(recCaptor.capture());
        assertEquals(WmsConstants.DEFECT_STATUS_DISPOSED, recCaptor.getValue().getStatus());
        assertEquals("M001", recCaptor.getValue().getMaterialCode());
    }

    /** 受托 SCRAP：受托方名下可用量不够 → 拒绝，不扣不记。 */
    @Test
    void consignedScrap_insufficient_rejected() {
        stubConsignorChain();
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                consignedRow("IV-A", "S1", "5")));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_SCRAP, "6"), "tester"));

        assertTrue(e.getMessage().contains("受托库存不足"), e.getMessage());
        verify(inventoryMapper, never()).deductQuantity(anyString(), any());
        verify(inventoryMapper, never()).upsertInventory(any());
        verify(defectRecordMapper, never()).insert(any(WmsDefectRecord.class));
    }

    /** 受托 RETURN：只登记台账（待处理），不扣受托库存、不写废品仓。 */
    @Test
    void consignedReturn_onlyRegistersRecord() {
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(supplier());

        service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_RETURN, "3"), "tester");

        ArgumentCaptor<WmsDefectRecord> recCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper).insert(recCaptor.capture());
        assertEquals(WmsConstants.DEFECT_STATUS_PENDING, recCaptor.getValue().getStatus());
        assertEquals(WmsConstants.DEFECT_DISPOSAL_RETURN, recCaptor.getValue().getDisposalType());
        verify(inventoryMapper, never()).deductQuantity(anyString(), any());
        verify(inventoryMapper, never()).upsertInventory(any());
    }

    /** 供应商反查失败必须拒绝：单不存在 / 缺受托方 / 受托方查无。 */
    @Test
    void consigned_supplierLookupFailure_rejected() {
        // 1) 来源单不存在
        when(inboundOrderMapper.selectOne(any())).thenReturn(null);
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_SCRAP, "1"), "tester"));
        assertTrue(e1.getMessage().contains("受托入库单不存在"), e1.getMessage());

        // 2) 单据缺少受托方
        WmsInboundOrder noSupplier = consignedOrder();
        noSupplier.setSupplierId(null);
        when(inboundOrderMapper.selectOne(any())).thenReturn(noSupplier);
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_SCRAP, "1"), "tester"));
        assertTrue(e2.getMessage().contains("缺少受托方"), e2.getMessage());

        // 3) 受托方查无
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(null);
        RuntimeException e3 = assertThrows(RuntimeException.class,
                () -> service.recordInbound(consignedDto(WmsConstants.DEFECT_DISPOSAL_SCRAP, "1"), "tester"));
        assertTrue(e3.getMessage().contains("受托方不存在"), e3.getMessage());

        verify(defectRecordMapper, never()).insert(any(WmsDefectRecord.class));
        verify(inventoryMapper, never()).deductQuantity(anyString(), any());
    }

    /** 生产 SCRAP：生产入库单不存在拒绝；存在时落自有废品仓（OWNED）并记台账（已处置）。 */
    @Test
    void productionScrap_validatesInboundAndWritesOwnedScrap() {
        when(materialMapper.selectById("MAT1")).thenReturn(
                new WmsMaterial().setMaterialCode("M001").setMaterialName("铜带").setUnit("米"));
        // 1) 生产入库单不存在 → 拒绝
        when(productionInboundMapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.recordInbound(
                new WmsDefectInboundDTO()
                        .setSourceType(WmsConstants.DEFECT_SOURCE_PRODUCTION)
                        .setSourceBillNo("SC999")
                        .setMaterialId("MAT1")
                        .setBatchNo("B1")
                        .setQty(BigDecimal.ONE)
                        .setDisposalType(WmsConstants.DEFECT_DISPOSAL_SCRAP), "tester"));
        assertTrue(e.getMessage().contains("生产入库单不存在"), e.getMessage());
        verify(inventoryMapper, never()).upsertInventory(any());

        // 2) 单据存在 → 落自有废品仓 + 记录已处置
        WmsProductionInbound inbound = new WmsProductionInbound().setInboundNo("SC999");
        when(productionInboundMapper.selectOne(any())).thenReturn(inbound);
        service.recordInbound(new WmsDefectInboundDTO()
                .setSourceType(WmsConstants.DEFECT_SOURCE_PRODUCTION)
                .setSourceBillNo("SC999")
                .setMaterialId("MAT1")
                .setBatchNo("B1")
                .setQty(new BigDecimal("2"))
                .setReason("尺寸不良")
                .setDisposalType(WmsConstants.DEFECT_DISPOSAL_SCRAP), "tester");
        ArgumentCaptor<WmsInventory> invCaptor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(invCaptor.capture());
        assertEquals(WmsConstants.OWNERSHIP_OWNED, invCaptor.getValue().getOwnershipType());
        assertEquals("", invCaptor.getValue().getConsignorId());
        ArgumentCaptor<WmsDefectRecord> recCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper).insert(recCaptor.capture());
        assertEquals(WmsConstants.DEFECT_SOURCE_PRODUCTION, recCaptor.getValue().getSourceType());
        assertEquals(WmsConstants.DEFECT_STATUS_DISPOSED, recCaptor.getValue().getStatus());
    }

    /** 生产来源不支持退货：退货仅适用受托。 */
    @Test
    void recordInbound_productionReturn_rejected() {
        when(productionInboundMapper.selectOne(any())).thenReturn(
                new WmsProductionInbound().setInboundNo("SC999"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.recordInbound(
                new WmsDefectInboundDTO()
                        .setSourceType(WmsConstants.DEFECT_SOURCE_PRODUCTION)
                        .setSourceBillNo("SC999")
                        .setMaterialId("MAT1")
                        .setQty(BigDecimal.ONE)
                        .setDisposalType(WmsConstants.DEFECT_DISPOSAL_RETURN), "tester"));
        assertTrue(e.getMessage().contains("生产不良只支持入废品仓"), e.getMessage());
    }

    // ------------------------------------------------------------ 退货

    /** 退货聚合：同来源单两条待退记录（同物料批次）共用一张退货单，数量合计成一条分录。 */
    @Test
    void return_aggregatesSameSourceBill_intoOneBill() {
        stubConsignorChain();
        when(defectRecordMapper.selectById("R2")).thenReturn(pendingRecord("R2", "M001", "B1", "3"));
        // 同来源单两条待退：R1(2) + R2(3) → 合计 5
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of())                                  // 第一次调用：已回填单号的记录
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "2"),
                        pendingRecord("R2", "M001", "B1", "3")));       // 第二次调用：待处理记录
        when(returnWriter.isEnabled()).thenReturn(true);
        when(returnWriter.existsByReference("SL20261104001")).thenReturn(false);
        when(returnWriter.write(any(), eq("C001"))).thenReturn(okResult("TH123"));
        when(formClient.submit(eq("PUR_ReturnBill"), anyList())).thenReturn(okResult("TH123"));

        WmsDefectRecord result = service.returnToConsignor("R2", "tester");

        ArgumentCaptor<WmsDefectRecord> payloadCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(returnWriter).write(payloadCaptor.capture(), eq("C001"));
        assertEquals(0, payloadCaptor.getValue().getQty().compareTo(new BigDecimal("5")));
        verify(formClient).submit("PUR_ReturnBill", List.of("TH123"));
        assertEquals("TH123", result.getReturnBillNo());
        assertEquals(WmsConstants.DEFECT_STATUS_RETURNED, result.getStatus());
        // 两条记录都要回填单号 + 置已退货（回填 2 次 + 置状态 2 次）
        ArgumentCaptor<WmsDefectRecord> patchCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper, times(4)).updateById(patchCaptor.capture());
        java.util.Set<String> ids = new java.util.HashSet<>();
        int returnedPatches = 0;
        for (WmsDefectRecord patch : patchCaptor.getAllValues()) {
            assertEquals("TH123", patch.getReturnBillNo());
            ids.add(patch.getId());
            if (WmsConstants.DEFECT_STATUS_RETURNED.equals(patch.getStatus())) {
                returnedPatches++;
            }
        }
        assertEquals(java.util.Set.of("R1", "R2"), ids);
        assertEquals(2, returnedPatches);
    }

    /** 退货沿用：同来源单已有退货单号 → 直接回存，不再调金蝶。 */
    @Test
    void return_reusesExistingReturnBillNo() {
        stubConsignorChain();
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "2"));
        WmsDefectRecord returnedEarlier = pendingRecord("R0", "M001", "B1", "1");
        returnedEarlier.setReturnBillNo("TH100");
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of(returnedEarlier))                    // 已回填单号的记录
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "2")));
        // Writer 未启用也照样沿用：单号是事实，不需要再碰金蝶

        WmsDefectRecord result = service.returnToConsignor("R1", "tester");

        assertEquals("TH100", result.getReturnBillNo());
        assertEquals(WmsConstants.DEFECT_STATUS_RETURNED, result.getStatus());
        verify(returnWriter, never()).write(any(), anyString());
        verify(returnWriter, never()).existsByReference(anyString());
        verify(formClient, never()).submit(anyString(), anyList());
        ArgumentCaptor<WmsDefectRecord> patchCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper).updateById(patchCaptor.capture());
        assertEquals("R1", patchCaptor.getValue().getId());
        assertEquals("TH100", patchCaptor.getValue().getReturnBillNo());
        assertEquals(WmsConstants.DEFECT_STATUS_RETURNED, patchCaptor.getValue().getStatus());
    }

    /** 金蝶查重命中（上次保存成功但本地回存失败）→ 不重复开单，置已退货并告警。 */
    @Test
    void return_existsByReferenceTrue_skipsWriteAndAlerts() {
        stubConsignorChain();
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "2"));
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "2")));
        when(returnWriter.isEnabled()).thenReturn(true);
        when(returnWriter.existsByReference("SL20261104001")).thenReturn(true);

        WmsDefectRecord result = service.returnToConsignor("R1", "tester");

        verify(returnWriter, never()).write(any(), anyString());
        verify(formClient, never()).submit(anyString(), anyList());
        assertEquals(WmsConstants.DEFECT_STATUS_RETURNED, result.getStatus());
        assertNull(result.getReturnBillNo());
        ArgumentCaptor<WmsDefectRecord> patchCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(defectRecordMapper).updateById(patchCaptor.capture());
        assertNull(patchCaptor.getValue().getReturnBillNo());
        assertEquals(WmsConstants.DEFECT_STATUS_RETURNED, patchCaptor.getValue().getStatus());
        verify(sysBaseAPI).sendSysAnnouncement(any());
    }

    /** 只有 CONSIGNED + RETURN + 待处理 的记录可以退货；反查链缺一环即拒绝。 */
    @Test
    void return_invalidRecord_rejected() {
        // 1) 生产来源不能退货
        WmsDefectRecord production = pendingRecord("R1", "M001", "B1", "1");
        production.setSourceType(WmsConstants.DEFECT_SOURCE_PRODUCTION);
        when(defectRecordMapper.selectById("R1")).thenReturn(production);
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e1.getMessage().contains("只有受托"), e1.getMessage());

        // 2) 处置方式不是退货
        WmsDefectRecord scrap = pendingRecord("R1", "M001", "B1", "1");
        scrap.setDisposalType(WmsConstants.DEFECT_DISPOSAL_SCRAP);
        when(defectRecordMapper.selectById("R1")).thenReturn(scrap);
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e2.getMessage().contains("处置方式不是退货"), e2.getMessage());

        // 3) 已退货不能重复退
        WmsDefectRecord done = pendingRecord("R1", "M001", "B1", "1");
        done.setStatus(WmsConstants.DEFECT_STATUS_RETURNED);
        when(defectRecordMapper.selectById("R1")).thenReturn(done);
        RuntimeException e3 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e3.getMessage().contains("不允许退货"), e3.getMessage());

        // 4) 缺来源单号（受托方定位链断裂）
        WmsDefectRecord noBill = pendingRecord("R1", "M001", "B1", "1");
        noBill.setSourceBillNo("");
        when(defectRecordMapper.selectById("R1")).thenReturn(noBill);
        RuntimeException e4 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e4.getMessage().contains("缺少来源单号"), e4.getMessage());
    }

    /** 受托方反查失败拒绝退货（退货比登记更依赖受托方：金蝶单据要挂供应商）。 */
    @Test
    void return_supplierLookupFailure_rejected() {
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "1"));
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "1")));
        when(inboundOrderMapper.selectOne(any())).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));

        assertTrue(e.getMessage().contains("受托入库单不存在"), e.getMessage());
        verify(returnWriter, never()).write(any(), anyString());
        verify(defectRecordMapper, never()).updateById(any(WmsDefectRecord.class));
    }

    /** 金蝶开关 / formId 未配置 → 显式失败，不静默跳过。 */
    @Test
    void return_kingdeeNotReady_rejected() {
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "1"));
        // 两次 returnToConsignor 调用都要走查重：奇数次=已回填单号查询（恒空）、偶数次=待处理查询（有记录），
        // 不能用 thenReturn 连续桩（耗尽后 Mockito 会重复最后一个值，污染第二次调用的查询）
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(defectRecordMapper.selectList(any())).thenAnswer(inv ->
                calls.incrementAndGet() % 2 == 1
                        ? List.of()
                        : List.of(pendingRecord("R1", "M001", "B1", "1")));
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(supplier());
        // isEnabled=false（mock 默认）
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e1.getMessage().contains("金蝶同步未启用"), e1.getMessage());

        // 开关开但 formId 未配置
        when(returnWriter.isEnabled()).thenReturn(true);
        when(configService.getString(eq("kingdee.form.purchase-return"), anyString())).thenReturn("");
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));
        assertTrue(e2.getMessage().contains("formId"), e2.getMessage());
    }

    /** 回写失败重试 3 次后仍失败 → 告警 + 抛错回滚。 */
    @Test
    void return_writeFailsAllRetries_alertsAndThrows() {
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "1"));
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "1")));
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(supplier());
        when(returnWriter.isEnabled()).thenReturn(true);
        when(returnWriter.existsByReference(anyString())).thenReturn(false);
        when(returnWriter.write(any(), anyString())).thenReturn(WmsKingdeeResult.fail("金蝶接口失败"));
        when(formClient.submit(anyString(), anyList())).thenReturn(WmsKingdeeResult.fail("金蝶接口失败"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.returnToConsignor("R1", "tester"));

        assertTrue(e.getMessage().contains("已重试 3 次"), e.getMessage());
        verify(returnWriter, times(3)).write(any(), anyString());
        verify(sysBaseAPI).sendSysAnnouncement(any());
        verify(defectRecordMapper, never()).updateById(any(WmsDefectRecord.class));
    }

    /** 提交失败后重试只补提交，不重复开单（单号已回存）。 */
    @Test
    void return_submitFailsThenSucceeds_singleBillNumber() {
        stubConsignorChain();
        when(defectRecordMapper.selectById("R1")).thenReturn(pendingRecord("R1", "M001", "B1", "1"));
        when(defectRecordMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(pendingRecord("R1", "M001", "B1", "1")));
        when(inboundOrderMapper.selectOne(any())).thenReturn(consignedOrder());
        when(supplierService.getById("S1")).thenReturn(supplier());
        when(returnWriter.isEnabled()).thenReturn(true);
        when(returnWriter.existsByReference(anyString())).thenReturn(false);
        when(returnWriter.write(any(), anyString())).thenReturn(okResult("TH777"));
        when(formClient.submit(anyString(), anyList()))
                .thenReturn(WmsKingdeeResult.fail("金蝶提交失败"))
                .thenReturn(okResult("TH777"));

        WmsDefectRecord result = service.returnToConsignor("R1", "tester");

        assertEquals("TH777", result.getReturnBillNo());
        verify(returnWriter, times(1)).write(any(), anyString());
        verify(formClient, times(2)).submit(anyString(), anyList());
    }

    // ------------------------------------------------------------ 生产提交写不良记录

    /** 生产入库提交 badQty>0 → 必须写一条 PRODUCTION 不良记录（来源单号=入库单号）。 */
    @Test
    void productionSubmit_writesDefectRecord() {
        WmsWorkOrderMapper workOrderMapper = mock(WmsWorkOrderMapper.class);
        WmsInventoryMapper prodInventoryMapper = mock(WmsInventoryMapper.class);
        WmsLocationMapper prodLocationMapper = mock(WmsLocationMapper.class);
        WmsWarehouseMapper prodWarehouseMapper = mock(WmsWarehouseMapper.class);
        WmsProductionInboundMapper prodInboundMapper = mock(WmsProductionInboundMapper.class);
        WmsDefectRecordMapper prodDefectRecordMapper = mock(WmsDefectRecordMapper.class);
        IWmsLocationService prodLocationService = mock(IWmsLocationService.class);
        IWmsSystemConfigService prodConfig = mock(IWmsSystemConfigService.class);
        ISysBaseAPI prodSysBaseAPI = mock(ISysBaseAPI.class);
        WmsProductionInboundServiceImpl prodService = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(prodService, "baseMapper", prodInboundMapper);
        WmsTestSupport.inject(prodService, "inboundMapper", prodInboundMapper);
        WmsTestSupport.inject(prodService, "workOrderMapper", workOrderMapper);
        WmsTestSupport.inject(prodService, "locationMapper", prodLocationMapper);
        WmsTestSupport.inject(prodService, "warehouseMapper", prodWarehouseMapper);
        WmsTestSupport.inject(prodService, "inventoryMapper", prodInventoryMapper);
        WmsTestSupport.inject(prodService, "locationService", prodLocationService);
        WmsTestSupport.inject(prodService, "systemConfigService", prodConfig);
        WmsTestSupport.inject(prodService, "defectRecordMapper", prodDefectRecordMapper);
        WmsTestSupport.inject(prodService, "sysBaseAPI", prodSysBaseAPI);

        WmsWorkOrder wo = new WmsWorkOrder()
                .setWorkOrderNo("WO1")
                .setPlanQty(new BigDecimal("100"))
                .setMaterialId("mat1")
                .setMaterialCode("M1")
                .setMaterialName("物料一")
                .setBatchNo("B1")
                .setWorkOrderStatus("已下达");
        when(workOrderMapper.selectByNoForUpdate("WO1")).thenReturn(wo);
        when(prodInboundMapper.sumInboundedQty("WO1")).thenReturn(BigDecimal.ZERO);
        when(prodInboundMapper.insert(any(WmsProductionInbound.class))).thenReturn(1);
        when(prodWarehouseMapper.selectOne(org.mockito.ArgumentMatchers
                .<Wrapper<WmsWarehouse>>any())).thenReturn(scrapWarehouse());
        when(prodLocationMapper.selectOne(org.mockito.ArgumentMatchers
                .<Wrapper<WmsLocation>>any())).thenReturn(scrapLocation());

        WmsProductionSubmitDTO dto = new WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO1");
        dto.setGoodQty(BigDecimal.ZERO);
        dto.setBadQty(new BigDecimal("2"));
        dto.setCarrierCode("RFID-1");
        dto.setBatchNo("B1");
        dto.setDefectRemark("划伤");
        prodService.submit(dto);

        ArgumentCaptor<WmsDefectRecord> recCaptor = ArgumentCaptor.forClass(WmsDefectRecord.class);
        verify(prodDefectRecordMapper).insert(recCaptor.capture());
        WmsDefectRecord defect = recCaptor.getValue();
        assertEquals(WmsConstants.DEFECT_SOURCE_PRODUCTION, defect.getSourceType());
        // 来源单号 = 生产入库单号（SC 前缀）
        assertTrue(defect.getSourceBillNo().startsWith("SC"), defect.getSourceBillNo());
        assertEquals("M1", defect.getMaterialCode());
        assertEquals(0, defect.getQty().compareTo(new BigDecimal("2")));
        assertEquals(WmsConstants.DEFECT_DISPOSAL_SCRAP, defect.getDisposalType());
        assertEquals(WmsConstants.DEFECT_STATUS_DISPOSED, defect.getStatus());
    }
}
