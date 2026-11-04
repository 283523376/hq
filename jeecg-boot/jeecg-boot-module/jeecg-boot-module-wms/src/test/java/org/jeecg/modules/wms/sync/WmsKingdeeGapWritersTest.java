package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
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
 * <p>锁三件事：
 * <ol>
 *   <li>formId 未配置时必须显式抛错，<b>不得返回空结果或静默成功</b>——静默会让「假成功」
 *       一路走到月末结账；</li>
 *   <li>formId 配置后，调用的是配置的单据、报文只含业务字段（不传 {@code FBillNo}，
 *       单号以 ERP 为准）；</li>
 *   <li>短装（0 数量）行跳过而不是整单失败，否则会出现「库存已入账、ERP 未回写」。</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsKingdeeGapWritersTest {

    private WmsKingdeeFormClient formClient;
    private IWmsSystemConfigService configService;
    private WmsInboundDetailMapper detailMapper;
    private WmsKingdeeProperties properties;

    private WmsKingdeeRawOrderReaderImpl reader;
    private WmsKingdeeConsignedInboundWriterImpl consignedWriter;
    private WmsKingdeePurchaseInboundWriterImpl purchaseInboundWriter;
    private WmsKingdeePurchaseReturnWriterImpl purchaseReturnWriter;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        configService = mock(IWmsSystemConfigService.class);
        detailMapper = mock(WmsInboundDetailMapper.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        reader = new WmsKingdeeRawOrderReaderImpl(formClient, configService, properties);
        consignedWriter = new WmsKingdeeConsignedInboundWriterImpl(
                formClient, configService, detailMapper, properties);
        purchaseInboundWriter = new WmsKingdeePurchaseInboundWriterImpl(
                formClient, configService, detailMapper, properties);
        purchaseReturnWriter = new WmsKingdeePurchaseReturnWriterImpl(
                formClient, configService, properties);
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
                () -> consignedWriter.write(consignedOrder()));

        assertTrue(e.getMessage().contains("受托加工材料入库单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND.getKey()), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void purchaseInboundWriterShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> purchaseInboundWriter.write(purchaseOrder()));

        assertTrue(e.getMessage().contains("采购入库单"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConfigKey.KD_FORM_PURCHASE_INBOUND.getKey()), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void purchaseReturnWriterShallFailExplicitlyWhenFormIdMissing() {
        stubEmptyConfig();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> purchaseReturnWriter.write(defectRecord(), "SUP01"));

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

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_ReceiveBill"), modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();
        assertFalse(model.containsKey("FBillNo"), "单号以 ERP 为准，不得传 FBillNo");
        assertTrue(String.valueOf(model.get("FNote")).contains("RK20261104001"), String.valueOf(model.get("FNote")));
        // 日期取审核时间，不取创建时间
        assertEquals("2026-11-03", model.get("FDate"));
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

        WmsKingdeeResult result = consignedWriter.write(consignedOrder());

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

        WmsKingdeeResult result = purchaseReturnWriter.write(defectRecord(), "SUP01");

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_MRRedReturn"), modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();
        assertFalse(model.containsKey("FBillNo"), "单号以 ERP 为准，不得传 FBillNo");
        // 备注恰好是幂等定位符，不拼原因等可变内容
        assertEquals("来源单:RK20261104001", model.get("FNote"));
        // 受托方由调用方传入
        assertEquals(Map.of("FNumber", "SUP01"), model.get("FSupplierId"));
        // 日期取记录创建时间，不取执行瞬间
        assertEquals("2026-11-02", model.get("FDate"));
        Map<String, Object> entry = firstEntry(model);
        assertEquals(Map.of("FNumber", "WA.1"), entry.get("FMaterialId"));
        assertEquals(new BigDecimal("2"), entry.get("FQty"));
        assertEquals(Map.of("FNumber", "B009"), entry.get("FLot"));
    }

    // ------------------------------------------------------------------ 短装行 / 校验

    /** 短装行（数量 0）跳过，其余行照常回写——整单失败会导致库存已入账而 ERP 未记账。 */
    @Test
    void purchaseInboundWriterShouldSkipShortPickRows() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("WA.1", "10", "0", "B001"),
                detail("WA.2", "4", "4", null)));
        when(formClient.save(eq("PUR_ReceiveBill"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CGRK0002", "1004"));

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_ReceiveBill"), modelCaptor.capture());
        Object entity = modelCaptor.getValue().get("FEntity");
        assertEquals(1, ((List<?>) entity).size(), "0 数量行必须跳过");
        Map<String, Object> entry = firstEntry(modelCaptor.getValue());
        assertEquals(Map.of("FNumber", "WA.2"), entry.get("FMaterialId"));
    }

    @Test
    void consignedInboundWriterShouldSkipShortPickRows() {
        stubConfig(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND, "SUB_ConsignedInbound");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("WA.1", "0", null, null),
                detail("WA.2", "3", null, null)));
        when(formClient.save(eq("SUB_ConsignedInbound"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "STRK0002", "1005"));

        WmsKingdeeResult result = consignedWriter.write(consignedOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("SUB_ConsignedInbound"), modelCaptor.capture());
        assertEquals(1, ((List<?>) modelCaptor.getValue().get("FEntity")).size());
    }

    /** 全部行都是 0：没有可回写内容，显式失败（不是「静默成功」）。 */
    @Test
    void purchaseInboundWriterShouldFailWhenAllRowsAreShortPick() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("WA.1", "10", "0", "B001"),
                detail("WA.2", "4", "0", null)));

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("没有可回写的明细"), result.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /** 负数数量是数据错误，不能被当成短装跳过。 */
    @Test
    void purchaseInboundWriterShouldRejectNegativeQuantity() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("WA.1", "-1", null, null)));

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("数量为负"), result.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /** 物料编码为空的明细行跳过并告警，不能 NPE、也不拖垮整单。 */
    @Test
    void purchaseInboundWriterShouldSkipRowsWithoutMaterialCode() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail(null, "10", "10", null),
                detail("WA.2", "2", "2", null)));
        when(formClient.save(eq("PUR_ReceiveBill"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CGRK0003", "1006"));

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_ReceiveBill"), modelCaptor.capture());
        assertEquals(1, ((List<?>) modelCaptor.getValue().get("FEntity")).size());
    }

    /**
     * 空物料行必须最先跳过：即使数量为负，也不该被数量校验拦下（该行本来就到不了金蝶）。
     * 这一条锁死「空物料 → 数量 → 短装」的处理顺序。
     */
    @Test
    void purchaseInboundWriterShouldSkipBlankMaterialBeforeQuantityCheck() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail(null, "-1", null, null)));

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("没有可回写的明细"), result.getMessage());
        assertFalse(result.getMessage().contains("数量为负"), "空物料行不应进入数量校验：" + result.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /** 纯空格的供应商不得发成 {FNumber: ""}（入库 Writer 同一口径）。 */
    @Test
    void purchaseInboundWriterShouldNotSendBlankSupplier() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("WA.1", "2", null, null)));
        when(formClient.save(eq("PUR_ReceiveBill"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CGRK0011", "1011"));
        WmsInboundOrder order = purchaseOrder();
        order.setSupplierCode("   ");

        assertTrue(purchaseInboundWriter.write(order).isSuccess());

        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_ReceiveBill"), modelCaptor.capture());
        assertFalse(modelCaptor.getValue().containsKey("FSupplierId"));
    }

    /** 来源类型不匹配必须抛错：采购单不能走受托入库、反之亦然。 */
    @Test
    void writersShouldRejectMismatchedSourceType() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        stubConfig(WmsConfigKey.KD_FORM_CONSIGNED_INBOUND, "SUB_ConsignedInbound");

        IllegalArgumentException purchase = assertThrows(IllegalArgumentException.class,
                () -> purchaseInboundWriter.write(consignedOrder()));
        assertTrue(purchase.getMessage().contains("source_type=PURCHASE"), purchase.getMessage());

        IllegalArgumentException consigned = assertThrows(IllegalArgumentException.class,
                () -> consignedWriter.write(purchaseOrder()));
        assertTrue(consigned.getMessage().contains("source_type=CONSIGNED"), consigned.getMessage());

        verify(formClient, never()).save(anyString(), any());
    }

    /** 报废记录不能走采购退货单。 */
    @Test
    void purchaseReturnWriterShouldRejectNonReturnDisposal() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        WmsDefectRecord scrap = defectRecord().setDisposalType(WmsConstants.DEFECT_DISPOSAL_SCRAP);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> purchaseReturnWriter.write(scrap, "SUP01"));

        assertTrue(e.getMessage().contains("disposal_type=RETURN"), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /** 明细缺失时显式失败：空报文 Save 会被金蝶报「分录不能为空」，不如本地说清原因。 */
    @Test
    void purchaseInboundWriterShouldFailWhenNoDetails() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_INBOUND, "PUR_ReceiveBill");
        when(detailMapper.selectList(any())).thenReturn(List.of());

        WmsKingdeeResult result = purchaseInboundWriter.write(purchaseOrder());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("明细"), result.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    // ------------------------------------------------------------------ 幂等查重 / 开关

    @Test
    void existsByReferenceShouldReturnTrueWhenBillExists() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.executeBillQuery(eq("PUR_MRRedReturn"), anyString(),
                contains("RK20261104001"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("CTHD0001")), null, null));

        assertTrue(purchaseReturnWriter.existsByReference("RK20261104001"));
    }

    @Test
    void existsByReferenceShouldReturnFalseWhenNoBill() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.executeBillQuery(eq("PUR_MRRedReturn"), anyString(),
                contains("RK20261104001"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        assertFalse(purchaseReturnWriter.existsByReference("RK20261104001"));
    }

    /** 查重过滤条件必须是等值匹配，且与写入的备注（来源单:<ref>）同一口径。 */
    @Test
    void existsByReferenceShouldUseEqualityFilter() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.executeBillQuery(eq("PUR_MRRedReturn"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        assertFalse(purchaseReturnWriter.existsByReference("RK20261104001"));

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PUR_MRRedReturn"), anyString(), filter.capture(), anyInt());
        assertEquals("FNote='来源单:RK20261104001'", filter.getValue());
    }

    /** 参考号字符集收紧：控制字符、引号、通配符、超长一律拒绝，不拼进查询。 */
    @Test
    void existsByReferenceShouldRejectInvalidReference() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");

        for (String bad : new String[]{"RK\n001", "RK'001", "A%B", "A B", "R".repeat(65)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> purchaseReturnWriter.existsByReference(bad), "应拒绝参考号：" + bad);
            assertTrue(e.getMessage().contains("不合法"), e.getMessage());
        }
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    /** write 侧与 exists 侧共用同一套来源单号校验。 */
    @Test
    void purchaseReturnWriterShouldRejectInvalidSourceBillNo() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> purchaseReturnWriter.write(defectRecord().setSourceBillNo("RK'001"), "SUP01"));

        assertTrue(e.getMessage().contains("不合法"), e.getMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /**
     * 往返自洽：write 写入的 FNote 恰好是「来源单:<ref>」，同一 ref 的等值查重必须命中。
     *
     * <p>模拟金蝶按等值匹配：只有过滤条件与写入备注完全一致才返回行；
     * 写单备注格式或查重过滤条件任一改动破坏自洽，本用例即失败。
     */
    @Test
    void existsByReferenceShouldMatchTheNoteWrittenByWrite() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.save(eq("PUR_MRRedReturn"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CTHD0009", "1009"));
        String ref = "RK_2026-1104";

        assertTrue(purchaseReturnWriter.write(defectRecord().setSourceBillNo(ref), "SUP01").isSuccess());

        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_MRRedReturn"), modelCaptor.capture());
        String note = String.valueOf(modelCaptor.getValue().get("FNote"));
        assertEquals("来源单:" + ref, note);

        when(formClient.executeBillQuery(eq("PUR_MRRedReturn"), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String filter = invocation.getArgument(2);
                    return ("FNote='" + note + "'").equals(filter)
                            ? WmsKingdeeResult.ok("[]", List.of(List.of("CTHD0009")), null, null)
                            : WmsKingdeeResult.ok("[]", List.of(), null, null);
                });

        assertTrue(purchaseReturnWriter.existsByReference(ref),
                "写单备注与查重过滤条件必须自洽，否则幂等保护形同虚设");
    }

    /** 纯空格的受托方不得发成 {FNumber: ""}。 */
    @Test
    void purchaseReturnWriterShouldNotSendBlankSupplier() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.save(eq("PUR_MRRedReturn"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "CTHD0010", "1010"));

        purchaseReturnWriter.write(defectRecord(), "   ");

        ArgumentCaptor<Map<String, Object>> modelCaptor = modelCaptor();
        verify(formClient).save(eq("PUR_MRRedReturn"), modelCaptor.capture());
        assertFalse(modelCaptor.getValue().containsKey("FSupplierId"));
    }

    /** 查重失败不能当成「不存在」，否则重试会重复开单。 */
    @Test
    void existsByReferenceShouldThrowWhenQueryFails() {
        stubConfig(WmsConfigKey.KD_FORM_PURCHASE_RETURN, "PUR_MRRedReturn");
        when(formClient.executeBillQuery(eq("PUR_MRRedReturn"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("金蝶超时"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> purchaseReturnWriter.existsByReference("RK20261104001"));

        assertTrue(e.getMessage().contains("查重失败"), e.getMessage());
    }

    /** isEnabled 直接映射 wms.sync.erp.kingdee.enabled，供调用方发起前判断。 */
    @Test
    void isEnabledShouldMirrorKingdeeSwitch() {
        assertTrue(reader.isEnabled());
        assertTrue(consignedWriter.isEnabled());
        assertTrue(purchaseInboundWriter.isEnabled());
        assertTrue(purchaseReturnWriter.isEnabled());

        properties.setEnabled(false);

        assertFalse(reader.isEnabled());
        assertFalse(consignedWriter.isEnabled());
        assertFalse(purchaseInboundWriter.isEnabled());
        assertFalse(purchaseReturnWriter.isEnabled());
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

    private WmsInboundOrder purchaseOrder() {
        return inboundOrder(WmsConstants.INBOUND_SOURCE_PURCHASE);
    }

    private WmsInboundOrder consignedOrder() {
        return inboundOrder(WmsConstants.INBOUND_SOURCE_CONSIGNED);
    }

    private WmsInboundOrder inboundOrder(String sourceType) {
        WmsInboundOrder order = new WmsInboundOrder();
        order.setId("IN001");
        order.setOrderNo("RK20261104001");
        order.setPurchaseOrderNo("CGDD001");
        order.setSupplierCode("SUP01");
        order.setSourceType(sourceType);
        order.setCreateTime(date("2026-11-04"));
        order.setAuditTime(date("2026-11-03"));
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
        record.setSourceType(WmsConstants.DEFECT_SOURCE_CONSIGNED);
        record.setSourceBillNo("RK20261104001");
        record.setMaterialCode("WA.1");
        record.setBatchNo("B009");
        record.setQty(new BigDecimal("2"));
        record.setReason("外观不良");
        record.setDisposalType(WmsConstants.DEFECT_DISPOSAL_RETURN);
        record.setCreateTime(date("2026-11-02"));
        return record;
    }

    private Date date(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
