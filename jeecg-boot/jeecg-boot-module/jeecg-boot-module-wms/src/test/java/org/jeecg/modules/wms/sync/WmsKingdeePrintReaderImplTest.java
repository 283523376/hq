package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶单据打印只读 Reader 单测（DY-001 Task 2）：
 * 过滤器转义+已审核门、行聚合、失败抛错、未知类型拒绝。
 *
 * <p>字段可用性已按测试账套实测（2026-11-09，探测脚本 tmp_files/print-field-probe*.py）：
 * 5 个 formId 的 FIELD_KEYS 以探测收敛结果为准；委外「订单编号/实发数量」、销售出库
 * 「客户订单号」、退货「备注」在测试账套元数据中不存在，模板列保留、值留空。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsKingdeePrintReaderImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeePrintReaderImpl reader;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = mock(WmsKingdeeProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        // 公共桩：默认空结果（避免未 stub 返回 null 让 reader 走进 NPE）
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        reader = new WmsKingdeePrintReaderImpl(formClient, properties);
    }

    /** 过滤器必须带单引号转义与已审核门（客户拍板：未审核单据不能打印）。 */
    @Test
    void queryApproved_buildsFilter_withEscapedQuote_andStatusC() {
        reader.queryApproved(WmsConstants.PRINT_BILL_LOSS, "QTCK'1");

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verifyQuery(formClient, filterCaptor);
        assertEquals("FBillNo='QTCK''1' AND FDocumentStatus='C'", filterCaptor.getValue());
    }

    /** 盘盈/盘亏/其他出入库按类型映射到正确的 formId 与字段键。 */
    @Test
    void queryApproved_mapsBillTypeToFormId_andFieldKeys() {
        reader.queryApproved(WmsConstants.PRINT_BILL_PROFIT, "QTRK10126110003");
        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(formClient).executeBillQuery(
                eq("STK_MISCELLANEOUS"), anyString(), filterCaptor.capture(), anyInt());
        assertTrue(filterCaptor.getValue().contains("QTRK10126110003"));

        reader.queryApproved(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868");
        org.mockito.Mockito.verify(formClient).executeBillQuery(
                eq("SAL_OUTSTOCK"), org.mockito.ArgumentMatchers.contains("FRealQty"),
                anyString(), anyInt());
    }

    /** 命中：笛卡尔行按字段键 zip 成 Map 聚合为一单（位置与 FIELD_KEYS 顺序对齐）。 */
    @Test
    void queryApproved_aggregatesRowsIntoMaps() {
        List<List<Object>> rows = List.of(
                List.of("QTCK10126110327", "C"),
                List.of("QTCK10126110327", "C"));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows, null, null));

        Optional<WmsKingdeePrintBill> hit = reader.queryApproved(
                WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126110327");

        assertTrue(hit.isPresent());
        assertEquals("QTCK10126110327", hit.get().getBillNo());
        assertEquals(2, hit.get().getRows().size());
        // 前两位与 FIELD_KEYS 顺序一致：FBillNo / FDocumentStatus
        assertEquals("QTCK10126110327", hit.get().getRows().get(0).get("FBillNo"));
        assertEquals("C", hit.get().getRows().get(1).get("FDocumentStatus"));
    }

    /** 查无（含未审核被过滤）→ empty；上层负责「不存在或未审核」合并文案。 */
    @Test
    void queryApproved_returnsEmpty_whenNoRows() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        assertFalse(reader.queryApproved(WmsConstants.PRINT_BILL_LOSS, "QTCK404").isPresent());
    }

    /** 金蝶调用失败 → 抛 RuntimeException（供上层回退快照）。 */
    @Test
    void queryApproved_throwsRuntime_whenKingdeeCallFails() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("网络超时"));
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.queryApproved(WmsConstants.PRINT_BILL_LOSS, "QTCK10126110002"));
        assertTrue(e.getMessage().contains("金蝶单据查询失败"), e.getMessage());
    }

    /** 未知类型拒绝；空单号拒绝；isEnabled 跟随配置。 */
    @Test
    void queryApproved_rejectsUnknownType_andBlankBillNo() {
        assertThrows(IllegalArgumentException.class,
                () -> reader.queryApproved("UNKNOWN_TYPE", "X"));
        assertThrows(IllegalArgumentException.class,
                () -> reader.queryApproved(WmsConstants.PRINT_BILL_LOSS, "  "));

        when(properties.isEnabled()).thenReturn(false);
        assertFalse(reader.isEnabled());
        when(properties.isEnabled()).thenReturn(true);
        assertTrue(reader.isEnabled());
    }

    /** 列表查询：已审核过滤 + 单号 like 模糊 + 稳定排序 + 多取 1 条（供 hasMore 判定）。 */
    @Test
    void queryApprovedPage_buildsFilterWithLike_andUsesStableOrder() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString())).thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        reader.queryApprovedPage(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10", "", 0, 20);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> start = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(formClient).executeBillQuery(
                eq("STK_MisDelivery"), anyString(), filter.capture(), limit.capture(),
                start.capture(), order.capture());
        assertEquals("FDocumentStatus='C' AND FBillNo like '%QTCK10%'", filter.getValue());
        assertEquals(0, start.getValue());
        assertEquals(21, limit.getValue(), "多取 1 条判断是否还有下一页");
        assertTrue(order.getValue().contains("FBillNo"), order.getValue());
    }

    /** 无关键词时只带已审核门；关键词单引号转义。 */
    @Test
    void queryApprovedPage_noKeyword_andEscapesQuote() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString())).thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        reader.queryApprovedPage(WmsConstants.PRINT_BILL_SALES_RETURN, "", "", 20, 20);
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(formClient).executeBillQuery(
                anyString(), anyString(), filter.capture(), anyInt(), anyInt(), anyString());
        assertEquals("FDocumentStatus='C'", filter.getValue());

        reader.queryApprovedPage(WmsConstants.PRINT_BILL_SALES_RETURN, "a'b", "", 0, 20);
        ArgumentCaptor<String> allFilters = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(formClient, org.mockito.Mockito.times(2)).executeBillQuery(
                anyString(), anyString(), allFilters.capture(), anyInt(), anyInt(), anyString());
        assertTrue(allFilters.getAllValues().get(1).contains("a''b"), allFilters.getAllValues().get(1));
    }

    /** 行映射：单号/日期/状态。 */
    @Test
    void queryApprovedPage_mapsRows() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(),
                anyString())).thenReturn(WmsKingdeeResult.ok("[]",
                List.of(List.of("QTCK000001", "2024-11-06T00:00:00", "C")), null, null));

        List<Map<String, String>> rows = reader.queryApprovedPage(
                WmsConstants.PRINT_BILL_MISC_DELIVERY, "", "", 0, 20);

        assertEquals(1, rows.size());
        assertEquals("QTCK000001", rows.get(0).get("FBillNo"));
        assertEquals("2024-11-06T00:00:00", rows.get(0).get("FDate"));
    }

    private static void verifyQuery(WmsKingdeeFormClient client, ArgumentCaptor<String> filterCaptor) {
        org.mockito.Mockito.verify(client).executeBillQuery(
                anyString(), anyString(), filterCaptor.capture(), anyInt());
    }
}
