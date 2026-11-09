package org.jeecg.modules.wms.service.impl;

import com.alibaba.fastjson2.JSON;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsPrintDocument;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.service.print.WmsPrintDataAssembler;
import org.jeecg.modules.wms.service.store.WmsPrintSnapshotStore;
import org.jeecg.modules.wms.sync.IWmsKingdeePrintReader;
import org.jeecg.modules.wms.sync.WmsKingdeePrintBill;
import org.jeecg.modules.wms.vo.WmsPrintBillListVO;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 单据打印编排服务单测（DY-001 Task 4）：本地入口解析、金蝶快照回退、
 * 销售出库本地兜底、本地下拉过滤。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsPrintServiceImplTest {

    private WmsPrintSnapshotStore store;
    private IWmsKingdeePrintReader reader;
    private WmsPrintDataAssembler assembler;
    private WmsStocktakeOrderMapper stocktakeMapper;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsMaterialMapper materialMapper;
    private IWmsSystemConfigService configService;
    private WmsPrintServiceImpl service;

    @BeforeEach
    void setUp() {
        store = mock(WmsPrintSnapshotStore.class);
        reader = mock(IWmsKingdeePrintReader.class);
        assembler = new WmsPrintDataAssembler();
        stocktakeMapper = mock(WmsStocktakeOrderMapper.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        configService = mock(IWmsSystemConfigService.class);
        when(configService.getString(anyString(), anyString())).thenReturn("");
        service = new WmsPrintServiceImpl(store, reader, assembler,
                stocktakeMapper, outboundMapper, detailMapper, materialMapper, configService);
        when(reader.isEnabled()).thenReturn(true);
    }

    // ------------------------------------------------------------ 盘盈/盘亏入口

    /** PD 单号 → 解析逗号合并的 erp_bill_no，按前缀取对应单号。 */
    @Test
    void inventoryEntry_resolvesErpBillNo_byPrefix() {
        WmsStocktakeOrder order = new WmsStocktakeOrder().setStocktakeNo("PD202611060002");
        order.setId("ST1");
        order.setErpBillNo("QTRK10126110003,QTCK10126110002");
        when(stocktakeMapper.selectOne(any())).thenReturn(order);
        when(reader.queryApproved(eq(WmsConstants.PRINT_BILL_LOSS), eq("QTCK10126110002")))
                .thenReturn(Optional.of(kingdeeBill(WmsConstants.PRINT_BILL_LOSS, "QTCK10126110002")));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_LOSS, "PD202611060002");

        assertEquals("盘亏单", vo.getTitle());
        assertEquals("QTCK10126110002", vo.getErpBillNo());
        verify(store).upsert(eq(WmsConstants.PRINT_BILL_LOSS), eq("QTCK10126110002"), anyString());
    }

    /** 未回写的盘点单：拒绝并给出可读文案。 */
    @Test
    void inventoryEntry_notWrittenBack_rejected() {
        WmsStocktakeOrder order = new WmsStocktakeOrder().setStocktakeNo("PD202611090001");
        order.setId("ST2");
        order.setErpBillNo(null);
        when(stocktakeMapper.selectOne(any())).thenReturn(order);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_PROFIT, "PD202611090001"));
        assertTrue(e.getMessage().contains("未回写金蝶"), e.getMessage());
        verify(reader, never()).queryApproved(anyString(), anyString());
    }

    /** 盘点单只回写了盘亏单却要打盘盈单：拒绝并说明缺该类型单据。 */
    @Test
    void inventoryEntry_missingTypeBill_rejected() {
        WmsStocktakeOrder order = new WmsStocktakeOrder().setStocktakeNo("PD202611060002");
        order.setId("ST1");
        order.setErpBillNo("QTCK10126110002");
        when(stocktakeMapper.selectOne(any())).thenReturn(order);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_PROFIT, "PD202611060002"));
        assertTrue(e.getMessage().contains("没有盘盈单"), e.getMessage());
    }

    // ------------------------------------------------------------ 金蝶链路与快照回退

    /** 金蝶失败但有快照：回退快照组装 + snapshotTime + warn。 */
    @Test
    void kingdeeDown_withSnapshot_fallsBack_andMarksSnapshotTime() {
        when(reader.queryApproved(anyString(), anyString()))
                .thenThrow(new RuntimeException("金蝶查询失败：网络超时"));
        Date snapTime = new Date();
        WmsPrintDocument snap = new WmsPrintDocument()
                .setBillType(WmsConstants.PRINT_BILL_MISC_DELIVERY)
                .setErpBillNo("QTCK10126110327")
                .setPayload(JSON.toJSONString(List.of(miscRow())))
                .setSnapshotTime(snapTime);
        snap.setId("DOC1");
        when(store.find(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126110327"))
                .thenReturn(Optional.of(snap));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126110327");

        assertEquals("其他出库单", vo.getTitle());
        assertEquals(snapTime, vo.getSnapshotTime());
        assertNotNull(vo.getExtras().get("warn"));
        assertTrue(String.valueOf(vo.getExtras().get("warn")).contains("网络超时"));
    }

    /** 金蝶失败且无快照：显式报错。 */
    @Test
    void kingdeeDown_withoutSnapshot_throws() {
        when(reader.queryApproved(anyString(), anyString()))
                .thenThrow(new RuntimeException("金蝶查询失败：网络超时"));
        when(store.find(anyString(), anyString())).thenReturn(Optional.empty());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_SALES_RETURN, "XSTHD10126110003"));
        assertTrue(e.getMessage().contains("金蝶查询失败"), e.getMessage());
    }

    /** 查无（不存在或未审核）：合并文案。 */
    @Test
    void kingdeeEmpty_reportsNotFoundOrUnapproved() {
        when(reader.queryApproved(anyString(), anyString())).thenReturn(Optional.empty());
        when(store.find(anyString(), anyString())).thenReturn(Optional.empty());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK404"));
        assertTrue(e.getMessage().contains("不存在或未审核"), e.getMessage());
    }

    /**
     * 评审 I1（Critical）：查空 ≠ 金蝶故障。单据被反审核/作废后必须**拒绝**，
     * 绝不能拿旧快照放行打印（会让已失效单据当凭证用）。
     */
    @Test
    void kingdeeEmpty_doesNotFallBackToSnapshot() {
        when(reader.queryApproved(anyString(), anyString())).thenReturn(Optional.empty());
        WmsPrintDocument stale = new WmsPrintDocument()
                .setBillType(WmsConstants.PRINT_BILL_MISC_DELIVERY)
                .setErpBillNo("QTCK10126110327")
                .setPayload(JSON.toJSONString(List.of(miscRow())))
                .setSnapshotTime(new Date());
        stale.setId("DOC9");
        when(store.find(anyString(), anyString())).thenReturn(Optional.of(stale));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126110327"));
        assertTrue(e.getMessage().contains("不存在或未审核"), e.getMessage());
    }

    /**
     * 评审 I2：快照回退必须保留模板 extras（联次行/公司信息块/logo），
     * 否则「离线重打」正好丢掉版式要素。
     */
    @Test
    void kingdeeDown_withSnapshot_keepsTemplateExtras() {
        when(reader.queryApproved(anyString(), anyString()))
                .thenThrow(new RuntimeException("金蝶查询失败：网络超时"));
        WmsPrintDocument snap = new WmsPrintDocument()
                .setBillType(WmsConstants.PRINT_BILL_SALES_OUTSTOCK)
                .setErpBillNo("XSCK1012619868")
                .setPayload(JSON.toJSONString(List.of(salesRow())))
                .setSnapshotTime(new Date());
        snap.setId("DOC3");
        when(store.find(anyString(), anyString())).thenReturn(Optional.of(snap));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868");

        assertNotNull(vo.getExtras().get("logo"), "回退后 logo 不能丢");
        assertNotNull(vo.getExtras().get("联次行"), "回退后联次行不能丢");
        assertNotNull(vo.getExtras().get("公司信息块"), "回退后公司信息块不能丢");
        assertNotNull(vo.getExtras().get("warn"));
    }

    /**
     * 评审 I3：本地单已回写金蝶时，以金蝶数据组装——打印编号用金蝶单号（spec §5-1），
     * 单价/货款有值（不是本地兜底的空串）。
     */
    @Test
    void salesOutstock_localBill_writtenBack_usesKingdeeData() {
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo("XS2118258526005280770")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SALES)
                .setErpBillNo("XSCK1012619868")
                .setCustomerName("健鼎（湖北）电子有限公司");
        order.setId("OB1");
        when(outboundMapper.selectOne(any())).thenReturn(order);
        when(reader.queryApproved(eq(WmsConstants.PRINT_BILL_SALES_OUTSTOCK), eq("XSCK1012619868")))
                .thenReturn(Optional.of(kingdeeBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868")));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XS2118258526005280770");

        assertEquals("XSCK1012619868", vo.getErpBillNo(), "打印编号必须是金蝶单号");
        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("0.68", first.get(3), "单价取自金蝶");
        assertEquals("17680", first.get(4), "货款取自金蝶");
    }

    /** 金蝶未启用：有快照走快照，无快照报未启用。 */
    @Test
    void kingdeeDisabled_usesSnapshot_orReportsDisabled() {
        when(reader.isEnabled()).thenReturn(false);
        when(store.find(anyString(), anyString())).thenReturn(Optional.empty());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_SUB_PICK, "WMLL00010491"));
        assertTrue(e.getMessage().contains("金蝶同步未启用"), e.getMessage());

        WmsPrintDocument snap = new WmsPrintDocument()
                .setBillType(WmsConstants.PRINT_BILL_SUB_PICK)
                .setPayload(JSON.toJSONString(List.of(subPickRow())))
                .setSnapshotTime(new Date());
        snap.setId("DOC2");
        when(store.find(anyString(), anyString())).thenReturn(Optional.of(snap));
        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SUB_PICK, "WMLL00010491");
        assertEquals("委外领料单", vo.getTitle());
        assertNotNull(vo.getSnapshotTime());
    }

    // ------------------------------------------------------------ 销售出库本地兜底

    /** 已回写金蝶的本地单：本地明细组装为底，金蝶补字段失败仅 warn 不阻断。 */
    @Test
    void salesOutstock_localBill_assemblesLocally_andWarnsWhenKingdeeFails() {
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo("XS2118258526005280770")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SALES)
                .setErpBillNo("XSCK1012619868")
                .setCustomerName("健鼎（湖北）电子有限公司");
        order.setId("OB1");
        when(outboundMapper.selectOne(any())).thenReturn(order);
        WmsOutboundDetail detail = new WmsOutboundDetail()
                .setMaterialId("MAT1").setMaterialCode("M001").setMaterialName("铣刀A")
                .setUnit("支").setActualQty(new BigDecimal("100"));
        detail.setId("D1");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail));
        when(reader.queryApproved(anyString(), anyString()))
                .thenThrow(new RuntimeException("金蝶查询失败：网络超时"));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XS2118258526005280770");

        assertEquals("销售出库单", vo.getTitle());
        // 已回写的单，打印编号用金蝶单号（spec §5-1）；本地号只作查询入口
        assertEquals("XSCK1012619868", vo.getErpBillNo());
        assertEquals("健鼎（湖北）电子有限公司", vo.getHeaders().get("购货单位"));
        assertNotNull(vo.getExtras().get("warn"), "金蝶取数失败必须提示但不阻断");
    }

    /** XSCK 金蝶单号（本地查无）：走金蝶链路并落快照。 */
    @Test
    void salesOutstock_erpBillNo_goesKingdeeRoute() {
        when(outboundMapper.selectOne(any())).thenReturn(null);
        when(reader.queryApproved(eq(WmsConstants.PRINT_BILL_SALES_OUTSTOCK), eq("XSCK1012619868")))
                .thenReturn(Optional.of(kingdeeBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868")));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868");

        assertEquals("销售出库单", vo.getTitle());
        verify(store).upsert(eq(WmsConstants.PRINT_BILL_SALES_OUTSTOCK), eq("XSCK1012619868"), anyString());
    }

    // ------------------------------------------------------------ 本地下拉

    /** 下拉：盘盈亏查已回写盘点单、销售出库查本地单；其他类型返回空。 */
    @Test
    void localBills_filtersByType() {
        WmsStocktakeOrder order = new WmsStocktakeOrder().setStocktakeNo("PD202611060002");
        order.setId("ST1");
        order.setErpBillNo("QTRK10126110003");
        when(stocktakeMapper.selectList(any())).thenReturn(List.of(order));

        List<Map<String, String>> stocktakes = service.localBills(WmsConstants.PRINT_BILL_PROFIT, "PD2026");
        assertEquals(1, stocktakes.size());
        assertEquals("PD202611060002", stocktakes.get(0).get("billNo"));
        assertEquals("1", stocktakes.get(0).get("printable"));

        assertTrue(service.localBills(WmsConstants.PRINT_BILL_MISC_DELIVERY, "x").isEmpty());
    }

    /** 公司名/公司信息块由系统配置覆盖（spec §5-5：换主体不改代码）。 */
    @Test
    void companyInfo_overriddenByConfig() {
        when(configService.getString(eq("print.company.name"), anyString())).thenReturn("测试主体有限公司");
        when(configService.getString(eq("print.company.info"), anyString()))
                .thenReturn("第一行提示\n第二行地址");
        when(reader.queryApproved(eq(WmsConstants.PRINT_BILL_SALES_OUTSTOCK), eq("XSCK1012619868")))
                .thenReturn(Optional.of(kingdeeBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868")));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868");

        assertEquals("测试主体有限公司", vo.getExtras().get("公司名"));
        assertEquals(List.of("第一行提示", "第二行地址"), vo.getExtras().get("公司信息块"));
    }

    /** 未配置时用模板内置默认（不因空配置丢信息）。 */
    @Test
    void companyInfo_fallsBackToBuiltInDefaults() {
        when(reader.queryApproved(eq(WmsConstants.PRINT_BILL_SALES_OUTSTOCK), eq("XSCK1012619868")))
                .thenReturn(Optional.of(kingdeeBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868")));

        WmsPrintDataVO vo = service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868");

        assertEquals("宜昌永鑫精工科技股份有限公司", vo.getExtras().get("公司名"));
        assertNotNull(vo.getExtras().get("公司信息块"));
    }

    /**
     * 本地草稿单禁止打印（2026-11-09 客户口径）：草稿是未生效单据，
     * 打印出去会被当凭证用。销售出库与盘点单同口径。
     */
    @Test
    void localDraftBills_rejected() {
        WmsOutboundOrder draft = new WmsOutboundOrder()
                .setOutboundNo("XS-DRAFT-1")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SALES)
                .setStatus(WmsConstants.OUTBOUND_STATUS_DRAFT);
        draft.setId("OB9");
        when(outboundMapper.selectOne(any())).thenReturn(draft);
        RuntimeException e1 = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XS-DRAFT-1"));
        assertTrue(e1.getMessage().contains("草稿"), e1.getMessage());

        WmsStocktakeOrder draftSt = new WmsStocktakeOrder().setStocktakeNo("PD-DRAFT-1")
                .setStatus(WmsConstants.STOCKTAKE_STATUS_DRAFT);
        draftSt.setId("ST9");
        when(stocktakeMapper.selectOne(any())).thenReturn(draftSt);
        RuntimeException e2 = assertThrows(RuntimeException.class,
                () -> service.printData(WmsConstants.PRINT_BILL_LOSS, "PD-DRAFT-1"));
        assertTrue(e2.getMessage().contains("草稿"), e2.getMessage());
    }

    /**
     * 工作台列表（2026-11-09）：本地类型分页 + 单号模糊；金蝶类型走 reader 分页并以 hasMore 判定。
     */
    @Test
    void bills_localType_pagesWithKeyword_andKingdeeTypeUsesHasMore() {
        // 本地：盘点单分页（mock selectPage 返回 1 条）
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsStocktakeOrder> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        WmsStocktakeOrder st = new WmsStocktakeOrder().setStocktakeNo("PD202611060002")
                .setStatus("已调整").setErpBillNo("QTCK10126110002");
        page.setRecords(List.of(st));
        page.setTotal(1);
        when(stocktakeMapper.selectPage(any(), any())).thenReturn(page);

        WmsPrintBillListVO local = service.bills(WmsConstants.PRINT_BILL_LOSS, "PD2026", 1, 20);
        assertEquals(1, local.getTotal());
        assertEquals("PD202611060002", local.getRecords().get(0).get("billNo"));
        assertEquals("20", String.valueOf(local.getPageSize()));

        // 金蝶：返回 21 条（多 1 条）→ 本页 20 条 + hasMore
        List<Map<String, String>> kdRows = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            kdRows.add(Map.of("FBillNo", "QTCK" + i, "FDate", "2024-11-06T00:00:00"));
        }
        when(reader.queryApprovedPage(eq(WmsConstants.PRINT_BILL_MISC_DELIVERY), eq("QTCK"), eq(0), eq(20)))
                .thenReturn(kdRows);

        WmsPrintBillListVO kd = service.bills(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK", 1, 20);
        assertEquals(20, kd.getRecords().size());
        assertTrue(kd.getHasMore());
        assertEquals(-1L, kd.getTotal());
    }

    /** 空参数拒绝。 */
    @Test
    void printData_rejectsBlankParams() {
        assertThrows(RuntimeException.class, () -> service.printData("", "X"));
        assertThrows(RuntimeException.class, () -> service.printData(WmsConstants.PRINT_BILL_LOSS, " "));
        assertThrows(RuntimeException.class, () -> service.printData("BAD_TYPE", "X"));
    }

    // ------------------------------------------------------------ 桩

    private WmsKingdeePrintBill kingdeeBill(String type, String billNo) {
        Map<String, String> row = WmsConstants.PRINT_BILL_SALES_OUTSTOCK.equals(type)
                ? salesRow() : (WmsConstants.PRINT_BILL_SUB_PICK.equals(type) ? subPickRow() : lossRow());
        return new WmsKingdeePrintBill().setBillType(type).setFormId("FORM")
                .setBillNo(billNo).setRows(List.of(row));
    }

    private Map<String, String> lossRow() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("FBillNo", "QTCK10126110002");
        r.put("FDate", "2026-11-09T00:00:00");
        r.put("FMaterialId.FNumber", "M001");
        r.put("FMaterialId.FName", "铜带");
        r.put("FUnitId.FName", "支");
        r.put("FQty", "1");
        return r;
    }

    private Map<String, String> miscRow() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("FBillNo", "QTCK10126110327");
        r.put("FDate", "2026-11-09T00:00:00");
        r.put("FDeptId.FName", "PCB非标车间");
        r.put("FMaterialId.FNumber", "Y.P.008.0101");
        r.put("FMaterialId.FName", "磨料");
        r.put("FUnitId.FName", "块");
        r.put("FQty", "1");
        return r;
    }

    private Map<String, String> subPickRow() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("FBillNo", "WMLL00010491");
        r.put("FDate", "2026-11-08T00:00:00");
        r.put("FSupplierId.FName", "东莞钠诺美特");
        r.put("FMaterialId.FNumber", "XA.00.01");
        r.put("FMaterialId.FName", "非涂层-整支");
        r.put("FUnitId.FName", "支");
        r.put("FAppQty", "8000");
        return r;
    }

    private Map<String, String> salesRow() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("FBillNo", "XSCK1012619868");
        r.put("FDate", "2026-11-09T00:00:00");
        r.put("FCustomerId.FName", "健鼎（湖北）电子有限公司");
        r.put("FMaterialId.FNumber", "L.40");
        r.put("FMaterialId.FName", "铣刀");
        r.put("FUnitId.FName", "支");
        r.put("FRealQty", "26000");
        r.put("FPrice", "0.68");
        r.put("FAmount", "17680");
        return r;
    }
}
