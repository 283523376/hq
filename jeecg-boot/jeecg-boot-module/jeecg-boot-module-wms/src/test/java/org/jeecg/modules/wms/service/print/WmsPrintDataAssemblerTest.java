package org.jeecg.modules.wms.service.print;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.sync.WmsKingdeePrintBill;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 打印数据组装器单测（DY-001 Task 3）——七类模板的列/头/脚与
 * 分页口径逐字对照客户样单（资料\需要打印的单据\）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
class WmsPrintDataAssemblerTest {

    private WmsPrintDataAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new WmsPrintDataAssembler();
    }

    // ------------------------------------------------------------ 盘盈/盘亏清单

    /** 盘盈/盘亏共用 12 列 xlsx 表头；数量列标题按类型切换。 */
    @Test
    void inventoryList_columns_followXlsxHeader_andKindSwitchesQtyTitle() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_PROFIT, "QTRK10126110003",
                row("FBillNo", "QTRK10126110003", "FDate", "2026-11-09", "FDeptId.FName", "PMC部",
                        "FMaterialId.FNumber", "M001", "FMaterialId.FName", "铜带",
                        "FMaterialId.FSpecification", "3.17*0.85", "FUnitId.FName", "支",
                        "FQty", "1", "FStockId.FName", "D.02"));

        WmsPrintDataVO profit = assembler.assembleInventoryList(WmsConstants.PRINT_BILL_PROFIT, bill, "PD202611090002");
        assertEquals(List.of("物料盘点作业编码", "单据编号", "日期", "仓库", "物料编码", "物料名称",
                "规格型号", "单位", "盘盈数量", "成本价", "总成本", "物料类别"), profit.getColumns());

        WmsPrintDataVO loss = assembler.assembleInventoryList(WmsConstants.PRINT_BILL_LOSS, bill, "PD202611090002");
        assertEquals("盘亏数量", loss.getColumns().get(8));
    }

    /** 行数据按表头顺序落位；盘点作业编码缺失时回退 WMS 盘点单号。 */
    @Test
    void inventoryList_rows_mapKingdeeEntries_withStocktakeNoFallback() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_PROFIT, "QTRK10126110003",
                row("FBillNo", "QTRK10126110003", "FDate", "2026-11-09",
                        "FMaterialId.FNumber", "M001", "FMaterialId.FName", "铜带",
                        "FMaterialId.FSpecification", "3.17*0.85", "FUnitId.FName", "支",
                        "FQty", "1", "FStockId.FName", "D.02"));

        WmsPrintDataVO vo = assembler.assembleInventoryList(WmsConstants.PRINT_BILL_PROFIT, bill, "PD202611090002");
        List<String> firstRow = vo.getPages().get(0).get(0);
        assertEquals("PD202611090002", firstRow.get(0), "盘点作业编码缺失时回落 WMS 盘点单号");
        assertEquals("QTRK10126110003", firstRow.get(1));
        assertEquals("2026/11/9", firstRow.get(2));
        assertEquals("D.02", firstRow.get(3), "仓库取收货仓库");
        assertEquals("M001", firstRow.get(4));
        assertEquals("铜带", firstRow.get(5));
        assertEquals("3.17*0.85", firstRow.get(6));
        assertEquals("支", firstRow.get(7));
        assertEquals("1", firstRow.get(8));
        assertEquals("", firstRow.get(9), "无成本字段时留空（空串而非 null，避免前端渲染 null）");
        assertEquals("QTRK10126110003", vo.getErpBillNo());
    }

    /** 评审 I6：成本价/总成本取金蝶分录自身字段（FPrice/FAmount），不再恒空。 */
    @Test
    void inventoryList_costColumns_filledFromEntries() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_LOSS, "QTCK000001",
                row("FBillNo", "QTCK000001", "FDate", "2024-11-06",
                        "FMaterialId.FNumber", "WA.2206", "FMaterialId.FName", "UN0.30*5.5-C",
                        "FUnitId.FName", "支", "FQty", "100",
                        "FPrice", "0.533681", "FAmount", "53.37"));

        WmsPrintDataVO vo = assembler.assembleInventoryList(WmsConstants.PRINT_BILL_LOSS, bill, null);

        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("0.533681", first.get(9), "成本价取分录 FPrice");
        assertEquals("53.37", first.get(10), "总成本取分录 FAmount");
    }

    /** 评审 Minor：数量去尾零（金蝶返回 26000.0 时打印 26000），与合计同格式。 */
    @Test
    void numbers_stripTrailingZeros_consistently() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK000001",
                row("FBillNo", "QTRK000001", "FMaterialId.FName", "料1", "FUnitId.FName", "支",
                        "FQty", "26000.0"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertEquals("26000", vo.getPages().get(0).get(0).get(4));
        assertEquals("26000", vo.getTotalsOnLastPage().get("数量"));
    }

    /** 客户样单日期为 2026/11/9 形态（斜杠、不补零）。 */
    @Test
    void dates_useSampleSlashStyle() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK000001",
                row("FBillNo", "QTRK000001", "FDate", "2026-11-06T00:00:00", "FQty", "1"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertEquals("2026/11/6", vo.getHeaders().get("日期"));
    }

    // ------------------------------------------------------------ 其他出库单

    /** 样单 QTCK10126110327：头=领用类型/部门/日期；脚=审核/仓管员/领料。 */
    @Test
    void miscDelivery_headersColumnsFooters_matchSample() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126110327",
                row("FBillNo", "QTCK10126110327", "FDate", "2026-11-09", "FDeptId.FName", "PCB非标车间",
                        "FBillTypeID.FName", "配件耗材领用单", "FApproverId.FName", "谭桂芳",
                        "FKeeperId.FName", "谭桂芳", "FPickerId.FName", "唐兵",
                        "FMaterialId.FNumber", "Y.P.008.0101", "FMaterialId.FName", "磨料",
                        "FMaterialId.FSpecification", "开槽砂轮", "FUnitId.FName", "块", "FQty", "1"));

        WmsPrintDataVO vo = assembler.assembleMiscDelivery(bill);

        assertEquals("其他出库单", vo.getTitle());
        assertEquals("QTCK10126110327", vo.getErpBillNo());
        assertEquals("配件耗材领用单", vo.getHeaders().get("领用类型"));
        assertEquals("PCB非标车间", vo.getHeaders().get("部门"));
        assertEquals("2026/11/9", vo.getHeaders().get("日期"));
        assertEquals(List.of("序号", "物料名称", "规格型号", "物料编码", "单位", "数量", "备注"), vo.getColumns());
        assertEquals("1", vo.getPages().get(0).get(0).get(0), "序号从 1 起");
        assertEquals("磨料", vo.getPages().get(0).get(0).get(1));
        assertEquals("1", vo.getTotalsOnLastPage().get("数量"));
        assertEquals(List.of("审核：谭桂芳", "仓管员：谭桂芳", "领料：唐兵"), vo.getFooters());
    }

    // ------------------------------------------------------------ 其他入库单

    /** 样单 QTRK10126110010：列含批号/收货仓库；脚=审核/验收/保管/制单。 */
    @Test
    void miscInstock_columnsAndFooters_matchSample() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK10126110010",
                row("FBillNo", "QTRK10126110010", "FDate", "2026-11-06", "FDeptId.FName", "PMC部",
                        "FApproverId.FName", "宋帆", "FKeeperId.FName", "陆丽君", "FCreatorId.FName", "陆丽君",
                        "FMaterialId.FNumber", "306.0409", "FMaterialId.FName", "精磨-焊接再生柄料",
                        "FMaterialId.FSpecification", "3.170*22.00", "FUnitId.FName", "支",
                        "FQty", "6120", "FLot.FNumber", "ST", "FStockId.FName", "Y原材料受托仓"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertEquals("其他入库单", vo.getTitle());
        assertEquals(List.of("物料名称", "规格型号", "物料编码", "单位", "数量", "批号", "收货仓库"), vo.getColumns());
        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("精磨-焊接再生柄料", first.get(0));
        assertEquals("ST", first.get(5));
        assertEquals("Y原材料受托仓", first.get(6));
        assertEquals("6120", vo.getTotalsOnLastPage().get("数量"));
        assertEquals(List.of("审核：宋帆", "验收：", "保管：陆丽君", "制单：陆丽君"), vo.getFooters());
    }

    // ------------------------------------------------------------ 委外领料单

    /** 样单 WMLL00010491：9 列；成品名称/规格取关联委外订单映射，缺则留空。 */
    @Test
    void subcontractPick_columnsAndProductMapping() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SUB_PICK, "WMLL00010491",
                row("FBillNo", "WMLL00010491", "FDate", "2026-11-08", "FSupplierId.FName", "东莞钠诺美特",
                        "FApproverId.FName", "古崇玉", "FCreatorId.FName", "古崇玉",
                        "FMaterialId.FNumber", "XA.00.01032101", "FMaterialId.FName", "非涂层-整支-平底型",
                        "FMaterialId.FSpecification", "非涂层-J-RPZ1", "FUnitId.FName", "支",
                        "FAppQty", "8000", "FStockId.FName", "F非标一部刀具成品仓"));

        WmsPrintDataVO vo = assembler.assembleSubcontractPick(bill, Map.of());

        assertEquals("委外领料单", vo.getTitle());
        assertEquals(List.of("订单编号", "物料编码", "加工材料名称", "加工材料规格", "实发数量",
                "加工成品名称", "成品规格", "单位", "发料仓库"), vo.getColumns());
        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("", first.get(0), "订单编号测试账套无字段，留空");
        assertEquals("非涂层-整支-平底型", first.get(2));
        assertEquals("8000", first.get(4));
        assertEquals("", first.get(5), "无订单映射时成品名称留空");
        assertFalse(vo.getFooters().isEmpty());
        assertTrue(vo.getFooters().get(0).startsWith("审核人："));
    }

    // ------------------------------------------------------------ 销售出库单

    /** 样单 XSCK1012619868：extras 带 logo/联次行/公司信息块；单价货款有值则填。 */
    @Test
    void salesOutstock_extrasAndPrice() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCK1012619868",
                row("FBillNo", "XSCK1012619868", "FDate", "2026-11-09", "FBillTypeID.FName", "标准销售出库单",
                        "FCustomerId.FName", "健鼎（湖北）电子有限公司", "FApproverId.FName", "王霞",
                        "FKeeperId.FName", "王霞", "FMaterialId.FNumber", "L.40*10.5",
                        "FMaterialId.FName", "铣刀1.40*10.5mm", "FMaterialId.FSpecification", "凤梨对接",
                        "FUnitId.FName", "支", "FRealQty", "26000", "FPrice", "0.68", "FAmount", "17680",
                        "FSrcBillNo", "XSDD000061"));

        WmsPrintDataVO vo = assembler.assembleSalesOutstock(bill);

        assertEquals("销售出库单", vo.getTitle());
        assertEquals("健鼎（湖北）电子有限公司", vo.getHeaders().get("购货单位"));
        assertEquals("标准销售出库单", vo.getHeaders().get("订单类型"));
        assertEquals(List.of("产品名称及规格", "单位", "数量", "单价", "货款", "备注"), vo.getColumns());
        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("铣刀1.40*10.5mm凤梨对接", first.get(0), "产品名称与规格拼接");
        assertEquals("26000", first.get(2));
        assertEquals("0.68", first.get(3));
        assertNotNull(vo.getExtras().get("logo"));
        assertNotNull(vo.getExtras().get("联次行"));
        assertNotNull(vo.getExtras().get("公司信息块"));
    }

    /** 本地销售出库单（未回写金蝶）：物料名称+规格拼接；价税留空。 */
    @Test
    void salesOutstockLocal_combinesNameAndSpec() {
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo("XS2118258526005280770")
                .setCustomerName("健鼎（湖北）电子有限公司")
                .setDeliveryDate(new java.util.Date(1794201600000L));   // 2026-11-09
        order.setId("OB1");
        WmsOutboundDetail d1 = new WmsOutboundDetail()
                .setMaterialCode("M001").setMaterialName("铣刀A").setUnit("支")
                .setActualQty(new BigDecimal("100"));
        WmsOutboundDetail d2 = new WmsOutboundDetail()
                .setMaterialCode("M002").setMaterialName("铣刀B").setUnit("支")
                .setActualQty(new BigDecimal("50"));

        WmsPrintDataVO vo = assembler.assembleSalesOutstockLocal(order, List.of(d1, d2),
                Map.of("M001", "凤梨对接"));

        assertEquals("XS2118258526005280770", vo.getErpBillNo());
        assertEquals("健鼎（湖北）电子有限公司", vo.getHeaders().get("购货单位"));
        List<String> first = vo.getPages().get(0).get(0);
        assertEquals("铣刀A凤梨对接", first.get(0));
        assertEquals("", first.get(3), "本地无单价，留空");
        assertEquals("150", vo.getTotalsOnLastPage().get("数量"));
        assertFalse(vo.getHeaders().get("日期").isEmpty(), "本地单也要有日期");
    }

    // ------------------------------------------------------------ 销售退货单

    @Test
    void salesReturn_columnsAndTotals() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SALES_RETURN, "XSTHD10126110003",
                row("FBillNo", "XSTHD10126110003", "FDate", "2026-11-03", "FSettleCustId.FName", "福建福强",
                        "FApproverId.FName", "刘琳", "FKeeperId.FName", "王霞",
                        "FMaterialId.FNumber", "XA.00.06010101", "FMaterialId.FName", "非涂层-无缝焊接",
                        "FMaterialId.FSpecification", "非涂层-CBR1.50", "FUnitId.FName", "支",
                        "FRealQty", "3950"));

        WmsPrintDataVO vo = assembler.assembleSalesReturn(bill);

        assertEquals("销售退货单", vo.getTitle());
        assertEquals("福建福强", vo.getHeaders().get("购货单位"));
        assertEquals(List.of("物料名称", "规格型号", "物料编码", "单位", "数量", "备注"), vo.getColumns());
        assertEquals("3950", vo.getTotalsOnLastPage().get("数量"));
    }

    // ------------------------------------------------------------ 分页

    /** 15 行 × 每页 12（盘盈清单默认行数）→ 2 页（12/3），末页补空行，合计只在末页且为全量。 */
    @Test
    void pagination_slicesRows_padsLastPage_andTotalsOnlyOnLastPage() {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            rows.add(row("FBillNo", "QTRK10126110010", "FDate", "2026-11-06",
                    "FMaterialId.FNumber", "M" + i, "FMaterialId.FName", "料" + i,
                    "FUnitId.FName", "支", "FQty", "10"));
        }
        WmsKingdeePrintBill bill = new WmsKingdeePrintBill()
                .setBillType(WmsConstants.PRINT_BILL_PROFIT)
                .setFormId("STK_MISCELLANEOUS")
                .setBillNo("QTRK10126110010")
                .setRows(rows);

        WmsPrintDataVO vo = assembler.assembleInventoryList(WmsConstants.PRINT_BILL_PROFIT, bill, null);

        assertEquals(WmsPrintDataAssembler.ROWS_PER_PAGE, vo.getRowsPerPage());
        assertEquals(2, vo.getTotalPages());
        assertEquals(2, vo.getPages().size());
        assertEquals(WmsPrintDataAssembler.ROWS_PER_PAGE, vo.getPages().get(0).size());
        assertEquals(WmsPrintDataAssembler.ROWS_PER_PAGE, vo.getPages().get(1).size(), "末页补空行到固定行数");
        assertEquals("M1", vo.getPages().get(0).get(0).get(4));
        assertEquals("M13", vo.getPages().get(1).get(0).get(4), "第二页从第 13 行起");
        assertEquals("", vo.getPages().get(1).get(3).get(4), "补的空行物料编码为空");
    }

    /** 销售退货单同样 6 行（客户 2026-11-09：与销售出库一致）。 */
    @Test
    void salesReturn_defaultsToSixRows_perPage() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SALES_RETURN, "XSTHD000001",
                row("FBillNo", "XSTHD000001", "FDate", "2024-11-21",
                        "FSettleCustId.FName", "乙客户", "FMaterialId.FName", "料",
                        "FUnitId.FName", "支", "FRealQty", "10"));

        WmsPrintDataVO vo = assembler.assembleSalesReturn(bill);

        assertEquals(6, vo.getRowsPerPage());
        assertEquals(6, vo.getPages().get(0).size());
    }

    /** 销售出库单默认只保留 6 行（客户 2026-11-09：12 行太密），其余类型仍 12 行。 */
    @Test
    void salesOutstock_defaultsToSixRows_perPage() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "XSCKD000001",
                row("FBillNo", "XSCKD000001", "FDate", "2024-09-02",
                        "FCustomerId.FName", "甲客户", "FMaterialId.FName", "铣刀",
                        "FUnitId.FName", "支", "FRealQty", "100"));

        WmsPrintDataVO vo = assembler.assembleSalesOutstock(bill);

        assertEquals(6, vo.getRowsPerPage());
        assertEquals(6, vo.getPages().get(0).size(), "表体只留 6 行（含数据行与空行）");
    }

    /** 销售出库单超过 6 行时分页（每页 6 行）。 */
    @Test
    void salesOutstock_overSixRows_paginates() {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rows.add(row("FBillNo", "XSCKD000001", "FMaterialId.FName", "铣刀" + i,
                    "FUnitId.FName", "支", "FRealQty", "10"));
        }
        WmsKingdeePrintBill bill = new WmsKingdeePrintBill()
                .setBillType(WmsConstants.PRINT_BILL_SALES_OUTSTOCK)
                .setFormId("SAL_OUTSTOCK").setBillNo("XSCKD000001").setRows(rows);

        WmsPrintDataVO vo = assembler.assembleSalesOutstock(bill);

        assertEquals(2, vo.getTotalPages());
        assertEquals(6, vo.getPages().get(0).size());
        assertEquals(6, vo.getPages().get(1).size());
        assertEquals("70", vo.getTotalsOnLastPage().get("数量"));
    }

    /** 单页单据也补满固定行数（样单风格）。 */
    @Test
    void pagination_singlePage_paddedToRowsPerPage() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK10126110010",
                row("FBillNo", "QTRK10126110010", "FMaterialId.FNumber", "M1",
                        "FMaterialId.FName", "料1", "FUnitId.FName", "支", "FQty", "5"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertEquals(1, vo.getTotalPages());
        assertEquals(6, vo.getPages().get(0).size(), "其他入库单表体固定 6 行");
        assertEquals("5", vo.getTotalsOnLastPage().get("数量"));
    }

    // ------------------------------------------------------------ 桩

    private WmsKingdeePrintBill printBill(String billType, String billNo, Map<String, String>... rows) {
        return new WmsKingdeePrintBill()
                .setBillType(billType)
                .setFormId("FORM")
                .setBillNo(billNo)
                .setRows(List.of(rows));
    }

    private Map<String, String> row(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    /** 其他出库单按实际行数出表，不补空行（客户 2026-11-09）。 */
    @Test
    void miscDelivery_noBlankRows() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_DELIVERY, "QTCK10126090100",
                row("FBillNo", "QTCK10126090100", "FDate", "2026-09-04",
                        "FMaterialId.FName", "微动开关", "FMaterialId.FNumber", "Y.P.010.0032",
                        "FUnitId.FName", "个", "FQty", "1"));

        WmsPrintDataVO vo = assembler.assembleMiscDelivery(bill);

        assertEquals(1, vo.getPages().get(0).size(), "只有 1 行数据就没有空行");
    }

    /** 委外领料单同样按实际行数出表，不补空行。 */
    @Test
    void subcontractPick_noBlankRows() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_SUB_PICK, "WWLL00008180",
                row("FBillNo", "WWLL00008180", "FDate", "2026-09-04",
                        "FMaterialId.FName", "料", "FUnitId.FName", "支", "FAppQty", "2"));

        WmsPrintDataVO vo = assembler.assembleSubcontractPick(bill, Map.of());

        assertEquals(1, vo.getPages().get(0).size(), "只有 1 行数据就没有空行");
    }

    /** 金蝶「保管者」被配成公司/组织时，落款留空（不能把公司名印在签名栏）。 */
    @Test
    void keeperConfiguredAsCompany_isBlankInFooters() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK000001",
                row("FBillNo", "QTRK000001", "FKeeperId.FName", "宜昌永鑫精工科技股份有限公司",
                        "FMaterialId.FName", "料", "FUnitId.FName", "支", "FQty", "1"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertTrue(vo.getFooters().stream().anyMatch(f -> f.equals("保管：")),
                "保管者为公司名时应留空，实际=" + vo.getFooters());
    }

    /** 保管者是人名时照常打印。 */
    @Test
    void keeperAsPerson_keptInFooters() {
        WmsKingdeePrintBill bill = printBill(WmsConstants.PRINT_BILL_MISC_INSTOCK, "QTRK000001",
                row("FBillNo", "QTRK000001", "FKeeperId.FName", "陆丽君",
                        "FMaterialId.FName", "料", "FUnitId.FName", "支", "FQty", "1"));

        WmsPrintDataVO vo = assembler.assembleMiscInstock(bill);

        assertTrue(vo.getFooters().contains("保管：陆丽君"), vo.getFooters().toString());
    }
}
