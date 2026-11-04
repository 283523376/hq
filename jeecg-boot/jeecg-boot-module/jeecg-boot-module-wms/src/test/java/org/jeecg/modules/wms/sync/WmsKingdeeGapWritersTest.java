package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶采购/受托/退货单据可配置 Reader/Writer 骨架单测（V2.4 spec §6）。
 *
 * <p>锁两件事：
 * <ol>
 *   <li>formId 未配置时必须显式抛错，<b>不得返回空结果或静默成功</b>——静默会让「假成功」
 *       一路走到月末结账；</li>
 *   <li>formId 配置后，调用的是配置的单据、报文只含业务字段（不传 {@code FBillNo}，
 *       单号以 ERP 为准）。</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsKingdeeGapWritersTest {

    private WmsKingdeeFormClient formClient;
    private IWmsSystemConfigService configService;
    private WmsInboundDetailMapper detailMapper;

    private WmsKingdeeRawOrderReaderImpl reader;
    private WmsKingdeeConsignedInboundWriterImpl consignedWriter;
    private WmsKingdeePurchaseInboundWriterImpl purchaseInboundWriter;
    private WmsKingdeePurchaseReturnWriterImpl purchaseReturnWriter;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        configService = mock(IWmsSystemConfigService.class);
        detailMapper = mock(WmsInboundDetailMapper.class);
        reader = new WmsKingdeeRawOrderReaderImpl(formClient, configService);
        consignedWriter = new WmsKingdeeConsignedInboundWriterImpl(formClient, configService, detailMapper);
        purchaseInboundWriter = new WmsKingdeePurchaseInboundWriterImpl(formClient, configService, detailMapper);
        purchaseReturnWriter = new WmsKingdeePurchaseReturnWriterImpl(formClient, configService);
    }

    /** 配置为空：getString 返回兜底值（真实实现里「未设置/空串」都走这条路径）。 */
    private void stubEmptyConfig() {
        when(configService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private void stubConfig(WmsConfigKey key, String formId) {
        when(configService.getString(eq(key.getKey()), anyString())).thenReturn(formId);
    }

    // ------------------------------------------------------------------ 未配置：显式失败

    @Test
    void queryPurchaseOrderShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> reader.queryPurchaseOrder("CGDD001"));

        assertTrue(e.getMessage().contains("采购订单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_PURCHASE_ORDER.getKey()), e.getMessage());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void queryConsignedOrderShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> reader.queryConsignedOrder("STDD001"));

        assertTrue(e.getMessage().contains("受托加工订单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_CONSIGNED_ORDER.getKey()), e.getMessage());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void consignedInboundWriterShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> consignedWriter.write(inboundOrder()));

        assertTrue(e.getMessage().contains("受托加工材料入库单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND.getKey()), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void purchaseInboundWriterShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> purchaseInboundWriter.write(inboundOrder()));

        assertTrue(e.getMessage().contains("采购入库单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void purchaseReturnWriterShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> purchaseReturnWriter.write(defectRecord()));

        assertTrue(e.getMessage().contains("采购退货单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_PURCHASE_RETURN.getKey()), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    // ------------------------------------------------------------------ 已配置：formId / 报文

    @Test
    void queryPurchaseOrderShouldUseConfiguredFormIdAndMinimalFields() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_ORDER, "PUR_PurchaseOrder");
        when(formClient.executeBillQuery(eq("PUR_PurchaseOrder"), anyString(), contains("CGDD001"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of("CGDD001", "WA.1", new BigDecimal("10"))), null, null));

        WmsKingdeeResult result = reader.queryPurchaseOrder("CGDD001");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getRows().size());
        ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PUR_PurchaseOrder"), fields.capture(),
                contains("CGDD001"), anyInt());
        assertTrue(fields.getValue().contains("FBillNo"), fields.getValue());
        assertTrue(fields.getValue().contains("FMaterialId.FNumber"), fields.getValue());
        assertTrue(fields.getValue().contains("FQty"), fields.getValue());
    }

    @Test
    void queryConsignedOrderShouldUseConfiguredFormId() {
        stubConfig(WmsConfigKey.KD_FORM_CONSIGNED_ORDER, "SUB_ConsignedOrder");
        when(formClient.executeBillQuery(eq("SUB_ConsignedOrder"), anyString(), contains("STDD001"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        WmsKingdeeResult result = reader.queryConsignedOrder("STDD001");

        assertTrue(result.isSuccess());
        verify(formClient).executeBillQuery(eq("SUB_ConsignedOrder"), anyString(),
                contains("STDD001"), anyInt());
    }

    @Test
    void purchaseInboundWriterShouldBuildBusinessPayloadWithoutBillNo() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("WA.1", "10", "8", "B001")));
        when(formClient.save(eq("PUR_ReceiveBill"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CGRK0001", "1001"));

        WmsKingdeeResult result = purchaseInboundWriter.write(inboundOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_ReceiveBill"), modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();
        assertFalse(model.containsKey("FBillNo"), "单号以 ERP 为准，不得传 FBillNo");
        assertTrue(String.valueOf(model.get("FNote")).contains("RK20261104001"), String.valueOf(model.get("FNote")));
        Map<String, Object> entry = firstEntry(model);
        assertEquals(Map.of("FNumber", "WA.1"), entry.get("FMaterialId"));
        // 实收数量优先于应收数量
        assertEquals(new BigDecimal("8"), entry.get("FQty"));
        assertEquals(Map.of("FNumber", "B001"), entry.get("FLot"));
    }

    @Test
    void consignedInboundWriterShouldBuildBusinessPayloadWithoutBillNo() {
        stubConfig(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND, "SUB_ConsignedInbound");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("WA.2", "5", null, null)));
        when(formClient.save(eq("SUB_ConsignedInbound"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "STRK0001", "1002"));

        WmsKingdeeResult result = consignedWriter.write(inboundOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("SUB_ConsignedInbound"), modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();
        assertFalse(model.containsKey("FBillNo"), "单号以 ERP 为准，不得传 FBillNo");
        Map<String, Object> entry = firstEntry(model);
        assertEquals(Map.of("FNumber", "WA.2"), entry.get("FMaterialId"));
        // 无实收数量时回退应收数量
        assertEquals(new BigDecimal("5"), entry.get("FQty"));
        assertFalse(entry.containsKey("FLot"), "无批次不得传空批次");
    }

    @Test
    void purchaseReturnWriterShouldBuildPayloadFromDefectRecord() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.save(eq("PUR_MRRedReturn"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CTHD0001", "1003"));

        WmsKingdeeResult result = purchaseReturnWriter.write(defectRecord());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_MRRedReturn"), modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();
        assertFalse(model.containsKey("FBillNo"), "单号以 ERP 为准，不得传 FBillNo");
        assertTrue(String.valueOf(model.get("FNote")).contains("RK20261104001"),
                String.valueOf(model.get("FNote")));
        Map<String, Object> entry = firstEntry(model);
        assertEquals(Map.of("FNumber", "WA.1"), entry.get("FMaterialId"));
        assertEquals(new BigDecimal("2"), entry.get("FQty"));
        assertEquals(Map.of("FNumber", "B009"), entry.get("FLot"));
    }

    /** 明细缺失时显式失败：空报文 Save 会被金蝶报「分录不能为空」，不如本地说清原因。 */
    @Test
    void purchaseInboundWriterShouldFailWhenNoDetails() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of());

        WmsKingdeeResult result = purchaseInboundWriter.write(inboundOrder());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("明细"), result.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    // ------------------------------------------------------------------ 夹具

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> modelCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstEntry(Map<String, Object> model) {
        Object entity = model.get("FEntity");
        assertNotNull(entity, "报文缺少 FEntity");
        assertTrue(entity instanceof List && !((List<?>) entity).isEmpty(), "报文 FEntity 为空");
        return (Map<String, Object>) ((List<?>) entity).get(0);
    }

    private WmsInboundOrder inboundOrder() {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setId("IN001");
        order.setOrderNo("RK20261104001");
        order.setPurchaseOrderNo("CGDD001");
        order.setSupplierCode("SUP01");
        order.setSourceType("PURCHASE");
        order.setCreateTime(new Date());
        return order;
    }

    private WmsInboundDetail detail(String materialCode, String quantity, String actualQuantity, String batchNo) {
        WmsInboundDetail detail = new WmsInboundDetail();
        detail.setInboundId("IN001");
        detail.setMaterialCode(materialCode);
        detail.setQuantity(quantity == null ? null : new BigDecimal(quantity));
        detail.setActualQuantity(actualQuantity == null ? null : new BigDecimal(actualQuantity));
        detail.setBatchNo(batchNo);
        return detail;
    }

    private WmsDefectRecord defectRecord() {
        WmsDefectRecord record = new WmsDefectRecord();
        record.setId("DF001");
        record.setSourceType("CONSIGNED");
        record.setSourceBillNo("RK20261104001");
        record.setMaterialCode("WA.1");
        record.setBatchNo("B009");
        record.setQty(new BigDecimal("2"));
        record.setReason("外观不良");
        record.setDisposalType("RETURN");
        return record;
    }
}
