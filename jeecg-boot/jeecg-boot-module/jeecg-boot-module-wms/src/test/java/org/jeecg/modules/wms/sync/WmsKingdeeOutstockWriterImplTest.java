package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SAL_OUTSTOCK 回写：单据类型、客户、仓库、批次与数量。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeeOutstockWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsMaterialCodeResolver materialResolver;
    private WmsKingdeeOutstockWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setOutStockBillType("XSCKD01_SYS");
        properties.setSaleOrgNumber("101");
        properties.setSettleCurrency("PRE001");
        properties.setDeliveryDeptId("10.10.11.11");
        properties.setStockerId("0136");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setMaterialMap(Map.of("FG001", "WA.FG"));
        lookup = mock(WmsKingdeeLookupSupport.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.resolveKingdeeCode(anyString())).thenAnswer(
                inv -> properties.getMaterialMap().getOrDefault((String) inv.getArgument(0), (String) inv.getArgument(0)));
        writer = new WmsKingdeeOutstockWriterImpl(formClient, properties, lookup, outboundMapper, materialResolver);

        when(lookup.material("WA.FG", "101")).thenReturn(List.of(999L, "WA.FG", "成品", 888L, 1L, "101"));
        when(lookup.unitNumber(888L)).thenReturn("PCS");
        when(lookup.stockCode("101", "agile")).thenReturn("D.01");
        when(lookup.customerId("01.001", "101")).thenReturn(777L);
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.save(eq("SAL_OUTSTOCK"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "XSCK001", "id1"));
        when(formClient.submit(eq("SAL_OUTSTOCK"), eq(List.of("XSCK001"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("XS1").setSalesOrderNo("SO1")
                .setCustomerCode("01.001");
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail() {
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("FG001")
                .setBatchNo("B1").setActualQty(new BigDecimal("8"));
        detail.setId("d1");
        return detail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_usesOutStockBillTypeAndCustomer() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("SAL_OUTSTOCK"), captor.capture());
        Map<String, Object> model = captor.getValue();
        // 单号以金蝶为准：不再传 FBillNo
        assertFalse(model.containsKey("FBillNo"));
        assertEquals("XSCKD01_SYS", ((Map<?, ?>) model.get("FBillTypeID")).get("FNumber"));
        // 基础资料按编码引用：真账套实测 Id 形式不绑定（2026-10-30）
        assertEquals("01.001", ((Map<?, ?>) model.get("FCustomerID")).get("FNumber"));
        assertEquals("10.10.11.11", ((Map<?, ?>) model.get("FDeliveryDeptID")).get("FNumber"));
        assertEquals("0136", ((Map<?, ?>) model.get("FStockerID")).get("FNumber"));
        // 财务信息是单值子实体：传数组金蝶报「不能转换为字典类型」（2026-10-30 真账套实测）
        Object subHead = model.get("SubHeadEntity");
        assertTrue(subHead instanceof Map, "SubHeadEntity 必须是字典，不能是数组");
        assertEquals("PRE001", ((Map<?, ?>) ((Map<?, ?>) subHead).get("FSettleCurrID")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.FG", ((Map<?, ?>) entry.get("FMaterialID")).get("FNumber"));
        assertEquals("D.01", ((Map<?, ?>) entry.get("FStockId")).get("FNumber"));
        assertEquals("B1", ((Map<?, ?>) entry.get("FLot")).get("FNumber"));
        assertEquals(0, ((BigDecimal) entry.get("FRealQty")).compareTo(new BigDecimal("8")));
        verify(formClient).submit("SAL_OUTSTOCK", List.of("XSCK001"));
    }

    @Test
    void write_missingDeptConfig_fails() {
        properties.setDeliveryDeptId("");

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置销售出库发货部门"));
    }


    @Test
    void write_submitFails_stillPersistsBillNo() {
        // 金蝶保存成功、提交失败（如信用超标）时，单号必须立刻回存：
        // 否则 WMS 丢了与 ERP 单据的关联，重试只会撞草稿查重，永远补不上提交。
        when(formClient.submit(eq("SAL_OUTSTOCK"), eq(List.of("XSCK001"))))
                .thenReturn(WmsKingdeeResult.fail("信用超标,需要进行密码特批处理"));

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        ArgumentCaptor<WmsOutboundOrder> patchCaptor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(outboundMapper, atLeastOnce()).updateById(patchCaptor.capture());
        assertTrue(patchCaptor.getAllValues().stream()
                        .anyMatch(p -> "XSCK001".equals(p.getErpBillNo())),
                "保存成功的单号必须回存，实际回存值：" + patchCaptor.getAllValues().stream()
                        .map(WmsOutboundOrder::getErpBillNo).toList());
    }

    // ---------- 关联开单（发货通知单来源，Link-Save） ----------

    private WmsOutboundOrder linkedOrder() {
        WmsOutboundOrder linked = order();
        linked.setSourceBillNo("FHTZD036453");
        return linked;
    }

    private void stubNoticeQuery(List<List<Object>> rows) {
        // 实现按「startRow + 稳定排序」翻页取分录
        when(formClient.executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_linkedEntries_whenSourceBillNoPresent() {
        stubNoticeQuery(List.of(List.of(136477, 151480, "WA.FG", 2000.0)));
        WmsOutboundDetail linkedDetail = detail();
        linkedDetail.setActualQty(new BigDecimal("500"));

        assertTrue(writer.write(linkedOrder(), List.of(linkedDetail)).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("SAL_OUTSTOCK"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertFalse(model.containsKey("FBillNo"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("SAL_DELIVERYNOTICE", entry.get("FSrcBillType"));
        assertEquals("FHTZD036453", entry.get("FSrcBillNo"));
        assertEquals(136477L, entry.get("FSrcInterId"));
        assertEquals(151480, entry.get("FSrcEntryId"));
        assertEquals(0, ((BigDecimal) entry.get("FQty")).compareTo(new BigDecimal("500")));
        assertEquals(0, ((BigDecimal) entry.get("FRealQty")).compareTo(new BigDecimal("500")));
        assertEquals("KCZT01_SYS", ((Map<?, ?>) entry.get("FStockStatusID")).get("FNumber"));
        List<Map<String, Object>> links = (List<Map<String, Object>>) entry.get("FEntity_Link");
        assertEquals(1, links.size());
        assertEquals("DeliveryNotice-OutStock", links.get(0).get("FEntity_Link_FRuleId"));
        assertEquals("T_SAL_DELIVERYNOTICEENTRY", links.get(0).get("FEntity_Link_FSTableName"));
        assertEquals("136477", links.get(0).get("FEntity_Link_FSBillId"));
        assertEquals("151480", links.get(0).get("FEntity_Link_FSId"));
        verify(formClient).submit("SAL_OUTSTOCK", List.of("XSCK001"));
    }

    @Test
    void write_materialMissingOnNotice_failsReadable() {
        stubNoticeQuery(List.of(List.of(136477, 151480, "OTHER.MATERIAL", 2000.0)));

        WmsOrderSyncResult result = writer.write(linkedOrder(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("FHTZD036453"));
        assertTrue(result.getErrorMessage().contains("WA.FG"));
        verify(formClient, never()).save(eq("SAL_OUTSTOCK"), any());
    }

    @Test
    void write_existingUnapprovedDraft_failsReadable() {
        stubNoticeQuery(List.of(List.of(136477, 151480, "WA.FG", 2000.0)));
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(),
                contains("FSrcBillNo='FHTZD036453'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("XSCK777", "A")), null, null));

        WmsOrderSyncResult result = writer.write(linkedOrder(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("XSCK777"));
        assertTrue(result.getErrorMessage().contains("已存在未审核"));
        verify(formClient, never()).save(eq("SAL_OUTSTOCK"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_linkedSpansMultipleNoticeEntries_forSameMaterial() {
        // 通知单同一物料分两行（不同批次/仓位）：100 + 1900。
        // 出库 1000 必须拆成两条金蝶分录（100 绑第一条、900 绑第二条），
        // 不能只拿第一条比余量（那会误报「剩余不足」），也不能两条都绑同一条（超额）。
        stubNoticeQuery(List.of(
                List.of(136477, 151480, "WA.FG", 100.0),
                List.of(136477, 151481, "WA.FG", 1900.0)));
        WmsOutboundDetail linkedDetail = detail();
        linkedDetail.setActualQty(new BigDecimal("1000"));

        assertTrue(writer.write(linkedOrder(), List.of(linkedDetail)).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("SAL_OUTSTOCK"), captor.capture());
        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) captor.getValue().get("FEntity");
        assertEquals(2, entries.size(), "跨分录必须拆成两条，实际：" + entries.size());
        assertEquals(151480, entries.get(0).get("FSrcEntryId"));
        assertEquals(0, ((BigDecimal) entries.get(0).get("FRealQty")).compareTo(new BigDecimal("100")));
        assertEquals(151481, entries.get(1).get("FSrcEntryId"));
        assertEquals(0, ((BigDecimal) entries.get(1).get("FRealQty")).compareTo(new BigDecimal("900")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_linkedAccumulatesAcrossDetails_onSameNoticeEntry() {
        // 两条 WMS 明细各 50，通知单只有一条分录 remain 60：
        // 第一条用掉 50，第二条只剩 10 → 必须报「剩余不足」，不能各自与 60 比较后都通过
        stubNoticeQuery(List.of(List.of(136477, 151480, "WA.FG", 60.0)));
        WmsOutboundDetail first = detail();
        first.setActualQty(new BigDecimal("50"));
        WmsOutboundDetail second = detail();
        second.setId("d2");
        second.setActualQty(new BigDecimal("50"));

        WmsOrderSyncResult result = writer.write(linkedOrder(), List.of(first, second));

        assertFalse(result.isSuccess(), "累计超出通知单剩余量时必须失败");
        assertTrue(result.getErrorMessage().contains("剩余"), "实际：" + result.getErrorMessage());
        verify(formClient, never()).save(eq("SAL_OUTSTOCK"), any());
    }

    @Test
    void write_invalidNoticeEntryId_failsReadable() {
        // 分录内码非数字：旧实现静默解析成 0，发出去 FSrcEntryId=0，金蝶报错不可读
        stubNoticeQuery(List.of(List.of(136477, "not-a-number", "WA.FG", 2000.0)));

        WmsOrderSyncResult result = writer.write(linkedOrder(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("分录"), "实际：" + result.getErrorMessage());
        verify(formClient, never()).save(eq("SAL_OUTSTOCK"), any());
    }

    @Test
    void write_pagesThroughNoticeEntries() {
        // 通知单分录超过一页（2000）时必须翻页，否则后面的分录被静默丢弃
        List<List<Object>> firstPage = new java.util.ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            firstPage.add(List.of(136477, 200000 + i, "OTHER." + i, 1.0));
        }
        when(formClient.executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", firstPage, null, null))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(136477, 151480, "WA.FG", 2000.0)), null, null));

        assertTrue(writer.write(linkedOrder(), List.of(detail())).isSuccess(),
                "第一页没有目标物料，必须继续翻页找到它");

        verify(formClient, atLeast(2)).executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), anyString(),
                anyInt(), anyInt(), anyString());
    }
}
