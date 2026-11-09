package org.jeecg.modules.wms.service.print;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.sync.WmsKingdeePrintBill;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 打印数据组装器（DY-001）：金蝶/本地单据 → 统一打印载荷。
 *
 * <p>版式口径逐字取自客户样单（{@code 资料\需要打印的单据\}）：
 * <ul>
 *   <li>列标题、签名行、公司信息块、联次行都与样单一致；</li>
 *   <li>合计只在末页；末页与单页都补空行到 {@link #ROWS_PER_PAGE}（样单空白行风格）；</li>
 *   <li>金蝶未提供的字段（成本价/单价/客户订单号等）一律留空，不阻断打印。</li>
 * </ul>
 * 纯函数无外部依赖——公司信息块等默认值由服务层按系统配置覆写 extras。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Component
public class WmsPrintDataAssembler {

    /** 每页固定行数（对齐样单；调整版式只改这里）。 */
    public static final int ROWS_PER_PAGE = 12;

    /** 销售出库单 logo（前端 public 资源相对路径，Task 6 模板引用）。 */
    private static final String SALES_LOGO = "youth-logo.png";

    private static final List<String> SALES_COPIES = List.of(
            "白色：存根", "红色：回单", "黄联：仓库", "绿联：客户");

    /** 公司信息块默认文案（逐字取自样单 XSCK1012619868；服务层可按配置覆盖）。 */
    private static final List<String> SALES_COMPANY_INFO = List.of(
            "如有数量问题请在24小时内通知我司，逾期后果自负",
            "工厂地址：湖北省宜都市红花套镇开发区永鑫工业园",
            "销售办事处地址：东莞市大岭山镇元新路2号创福汇10楼1001室",
            "工厂电话：0717-48825188",
            "工厂传真：0717-4815666");

    // ------------------------------------------------------------ 盘盈/盘亏清单（xlsx 表头）

    /**
     * 盘盈/盘亏清单：12 列照客户 xlsx 表头；成本价/总成本本期留空（金蝶未提供取数口径）。
     *
     * @param billType  PRINT_BILL_PROFIT（盘盈数量）/ PRINT_BILL_LOSS（盘亏数量）
     * @param bill      金蝶单据行
     * @param stocktakeNo WMS 盘点单号（表头「物料盘点作业编码」缺失时回落）
     */
    public WmsPrintDataVO assembleInventoryList(String billType, WmsKingdeePrintBill bill,
                                                String stocktakeNo) {
        boolean profit = WmsConstants.PRINT_BILL_PROFIT.equals(billType);
        List<String> columns = List.of("物料盘点作业编码", "单据编号", "日期", "仓库", "物料编码",
                "物料名称", "规格型号", "单位", profit ? "盘盈数量" : "盘亏数量",
                "成本价", "总成本", "物料类别");
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        for (Map<String, String> r : rows) {
            String jobNo = firstNonBlank(get(r, "FStockTakeNo"), get(r, "FStocktakeNo"), stocktakeNo);
            List<String> line = new ArrayList<>(columns.size());
            line.add(nvl(jobNo));
            line.add(nvl(get(r, "FBillNo")));
            line.add(dateOnly(get(r, "FDate")));
            line.add(nvl(get(r, "FStockId.FName")));
            line.add(nvl(get(r, "FMaterialId.FNumber")));
            line.add(nvl(get(r, "FMaterialId.FName")));
            line.add(nvl(get(r, "FMaterialId.FSpecification")));
            line.add(nvl(get(r, "FUnitId.FName")));
            line.add(nvl(get(r, "FQty")));
            line.add("");
            line.add("");
            line.add("");
            body.add(line);
        }
        WmsPrintDataVO vo = base(billType, profit ? "盘盈单" : "盘亏单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("日期", dateOnly(get(first(rows), "FDate")));
        finish(vo, body);
        return vo;
    }

    // ------------------------------------------------------------ 其他出库单（样单 QTCK10126110327）

    public WmsPrintDataVO assembleMiscDelivery(WmsKingdeePrintBill bill) {
        List<String> columns = List.of("序号", "物料名称", "规格型号", "物料编码", "单位", "数量", "备注");
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        int seq = 0;
        for (Map<String, String> r : rows) {
            BigDecimal qty = num(get(r, "FQty"));
            total = total.add(qty);
            body.add(List.of(String.valueOf(++seq),
                    nvl(get(r, "FMaterialId.FName")),
                    nvl(get(r, "FMaterialId.FSpecification")),
                    nvl(get(r, "FMaterialId.FNumber")),
                    nvl(get(r, "FUnitId.FName")),
                    nvl(get(r, "FQty")),
                    nvl(get(r, "FEntryNote")).trim()));
        }
        Map<String, String> head = first(rows);
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_MISC_DELIVERY, "其他出库单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("领用类型", firstNonBlank(get(head, "FBillTypeID.FName"), "配件耗材领用单"));
        vo.getHeaders().put("部门", nvl(get(head, "FDeptId.FName")));
        vo.getHeaders().put("日期", dateOnly(get(head, "FDate")));
        vo.setFooters(List.of(
                "审核：" + nvl(get(head, "FApproverId.FName")),
                "仓管员：" + nvl(get(head, "FKeeperId.FName")),
                "领料：" + nvl(get(head, "FPickerId.FName"))));
        finish(vo, body);
        vo.getTotalsOnLastPage().put("数量", plain(total));
        return vo;
    }

    // ------------------------------------------------------------ 其他入库单（样单 QTRK10126110010）

    public WmsPrintDataVO assembleMiscInstock(WmsKingdeePrintBill bill) {
        List<String> columns = List.of("物料名称", "规格型号", "物料编码", "单位", "数量", "批号", "收货仓库");
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, String> r : rows) {
            total = total.add(num(get(r, "FQty")));
            body.add(List.of(
                    nvl(get(r, "FMaterialId.FName")),
                    nvl(get(r, "FMaterialId.FSpecification")),
                    nvl(get(r, "FMaterialId.FNumber")),
                    nvl(get(r, "FUnitId.FName")),
                    nvl(get(r, "FQty")),
                    nvl(get(r, "FLot.FNumber")),
                    nvl(get(r, "FStockId.FName"))));
        }
        Map<String, String> head = first(rows);
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_MISC_INSTOCK, "其他入库单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("部门", nvl(get(head, "FDeptId.FName")));
        vo.getHeaders().put("日期", dateOnly(get(head, "FDate")));
        vo.setFooters(List.of(
                "审核：" + nvl(get(head, "FApproverId.FName")),
                "验收：",
                "保管：" + nvl(get(head, "FKeeperId.FName")),
                "制单：" + nvl(get(head, "FCreatorId.FName"))));
        finish(vo, body);
        vo.getTotalsOnLastPage().put("数量", plain(total));
        return vo;
    }

    // ------------------------------------------------------------ 委外领料单（样单 WMLL00010491）

    /**
     * 委外领料单：9 列。测试账套元数据无订单编号/数量字段——订单编号留空、
     * 实发数量取 FAppQty；成品名称/规格按订单编号查映射，查不到留空。
     *
     * @param productByOrderNo 订单编号 → "成品名称|成品规格"
     */
    public WmsPrintDataVO assembleSubcontractPick(WmsKingdeePrintBill bill,
                                                  Map<String, String> productByOrderNo) {
        List<String> columns = List.of("订单编号", "物料编码", "加工材料名称", "加工材料规格", "实发数量",
                "加工成品名称", "成品规格", "单位", "发料仓库");
        Map<String, String> productMap = productByOrderNo == null ? Collections.emptyMap() : productByOrderNo;
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, String> r : rows) {
            String orderNo = nvl(firstNonBlank(get(r, "FOrderBillNo"), get(r, "FOrderNo")));
            String qtyText = firstNonBlank(get(r, "FQty"), get(r, "FAppQty"));
            total = total.add(num(qtyText));
            String product = productMap.getOrDefault(orderNo, "|");
            String[] parts = product.split("\\|", -1);
            body.add(List.of(orderNo,
                    nvl(get(r, "FMaterialId.FNumber")),
                    nvl(get(r, "FMaterialId.FName")),
                    nvl(get(r, "FMaterialId.FSpecification")),
                    nvl(qtyText),
                    parts.length > 0 ? nvl(parts[0]) : "",
                    parts.length > 1 ? nvl(parts[1]) : "",
                    nvl(get(r, "FUnitId.FName")),
                    nvl(get(r, "FStockId.FName"))));
        }
        Map<String, String> head = first(rows);
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_SUB_PICK, "委外领料单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("加工单位", nvl(get(head, "FSupplierId.FName")));
        vo.getHeaders().put("业务类型", "普通委外领料");
        vo.getHeaders().put("日期", dateOnly(get(head, "FDate")));
        vo.setFooters(List.of(
                "审核人：" + nvl(get(head, "FApproverId.FName")),
                "发料人：",
                "制单人：" + nvl(get(head, "FCreatorId.FName"))));
        finish(vo, body);
        vo.getTotalsOnLastPage().put("实发数量", plain(total));
        return vo;
    }

    // ------------------------------------------------------------ 销售出库单（样单 XSCK1012619868）

    public WmsPrintDataVO assembleSalesOutstock(WmsKingdeePrintBill bill) {
        List<String> columns = List.of("产品名称及规格", "单位", "数量", "单价", "货款", "备注");
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, String> r : rows) {
            totalQty = totalQty.add(num(get(r, "FRealQty")));
            totalAmount = totalAmount.add(num(get(r, "FAmount")));
            body.add(List.of(
                    joinNameSpec(get(r, "FMaterialId.FName"), get(r, "FMaterialId.FSpecification")),
                    nvl(get(r, "FUnitId.FName")),
                    nvl(get(r, "FRealQty")),
                    nvl(get(r, "FPrice")),
                    nvl(get(r, "FAmount")),
                    nvl(get(r, "FEntryNote")).trim()));
        }
        Map<String, String> head = first(rows);
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "销售出库单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("购货单位", nvl(firstNonBlank(get(head, "FCustomerId.FName"), get(head, "FSettleCustId.FName"))));
        vo.getHeaders().put("订单类型", firstNonBlank(get(head, "FBillTypeID.FName"), "普通销售"));
        vo.getHeaders().put("客户订单号", nvl(get(head, "FCustOrderNo")));
        vo.getHeaders().put("日期", dateOnly(get(head, "FDate")));
        vo.setFooters(List.of(
                "客户签收盖章：",
                "发货签名：",
                "放行签名：",
                "保管员：" + nvl(get(head, "FKeeperId.FName")),
                "送提货人签名："));
        vo.getExtras().put("logo", SALES_LOGO);
        vo.getExtras().put("联次行", SALES_COPIES);
        vo.getExtras().put("公司信息块", SALES_COMPANY_INFO);
        finish(vo, body);
        vo.getTotalsOnLastPage().put("数量", plain(totalQty));
        if (totalAmount.signum() > 0) {
            vo.getTotalsOnLastPage().put("货款", plain(totalAmount));
        }
        return vo;
    }

    /**
     * 本地销售出库单（WMS 已建单但未回写金蝶）：名称+规格拼接，单价/货款留空
     * （金蝶补充由服务层负责，查不到就空着，不阻断打印）。
     *
     * @param specByMaterialCode 物料编码 → 规格（从物料主数据补）
     */
    public WmsPrintDataVO assembleSalesOutstockLocal(WmsOutboundOrder order,
                                                     List<WmsOutboundDetail> details,
                                                     Map<String, String> specByMaterialCode) {
        Map<String, String> specMap = specByMaterialCode == null ? Collections.emptyMap() : specByMaterialCode;
        List<List<String>> body = new ArrayList<>(details.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        for (WmsOutboundDetail d : details) {
            BigDecimal qty = d.getActualQty() == null ? BigDecimal.ZERO : d.getActualQty();
            totalQty = totalQty.add(qty);
            body.add(List.of(
                    joinNameSpec(d.getMaterialName(), specMap.get(d.getMaterialCode())),
                    nvl(d.getUnit()),
                    plain(qty),
                    "", "", ""));
        }
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "销售出库单", order.getOutboundNo());
        vo.setColumns(List.of("产品名称及规格", "单位", "数量", "单价", "货款", "备注"));
        vo.getHeaders().put("购货单位", nvl(order.getCustomerName()));
        vo.getHeaders().put("订单类型", "普通销售");
        vo.getHeaders().put("客户订单号", "");
        vo.getHeaders().put("日期", "");
        vo.setFooters(List.of("客户签收盖章：", "发货签名：", "放行签名：", "保管员：", "送提货人签名："));
        vo.getExtras().put("logo", SALES_LOGO);
        vo.getExtras().put("联次行", SALES_COPIES);
        vo.getExtras().put("公司信息块", SALES_COMPANY_INFO);
        finish(vo, body);
        vo.getTotalsOnLastPage().put("数量", plain(totalQty));
        return vo;
    }

    // ------------------------------------------------------------ 销售退货单（样单 XSTHD10126110003）

    public WmsPrintDataVO assembleSalesReturn(WmsKingdeePrintBill bill) {
        List<String> columns = List.of("物料名称", "规格型号", "物料编码", "单位", "数量", "备注");
        List<Map<String, String>> rows = rowsOf(bill);
        List<List<String>> body = new ArrayList<>(rows.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        for (Map<String, String> r : rows) {
            totalQty = totalQty.add(num(get(r, "FRealQty")));
            body.add(List.of(
                    nvl(get(r, "FMaterialId.FName")),
                    nvl(get(r, "FMaterialId.FSpecification")),
                    nvl(get(r, "FMaterialId.FNumber")),
                    nvl(get(r, "FUnitId.FName")),
                    nvl(get(r, "FRealQty")),
                    nvl(get(r, "FEntryNote")).trim()));
        }
        Map<String, String> head = first(rows);
        WmsPrintDataVO vo = base(WmsConstants.PRINT_BILL_SALES_RETURN, "销售退货单", bill.getBillNo());
        vo.setColumns(columns);
        vo.getHeaders().put("购货单位", nvl(firstNonBlank(get(head, "FSettleCustId.FName"), get(head, "FCustomerId.FName"))));
        vo.getHeaders().put("日期", dateOnly(get(head, "FDate")));
        vo.setFooters(List.of(
                "保管员：" + nvl(get(head, "FKeeperId.FName")),
                "送提货人签名：",
                "发货签名："));
        vo.getExtras().put("公司信息块", List.of(
                "如有数量问题请在24小时内通知我司，逾期后果自负",
                "工厂地址：湖北省宜都市红花套镇永鑫工业园"));
        finish(vo, body);
        vo.getTotalsOnLastPage().put("数量", plain(totalQty));
        return vo;
    }

    // ------------------------------------------------------------ 内部支撑

    private WmsPrintDataVO base(String billType, String title, String billNo) {
        return new WmsPrintDataVO()
                .setBillType(billType)
                .setTitle(title)
                .setErpBillNo(billNo);
    }

    /** 分页 + 末页补空行 + 回填 totalPages/rowsPerPage。 */
    private void finish(WmsPrintDataVO vo, List<List<String>> body) {
        int pageCount = Math.max(1, (int) Math.ceil(body.size() / (double) ROWS_PER_PAGE));
        List<List<List<String>>> pages = new ArrayList<>(pageCount);
        for (int p = 0; p < pageCount; p++) {
            int from = p * ROWS_PER_PAGE;
            int to = Math.min(from + ROWS_PER_PAGE, body.size());
            List<List<String>> page = new ArrayList<>(body.subList(from, to));
            while (page.size() < ROWS_PER_PAGE) {
                page.add(blankRow(vo.getColumns().size()));
            }
            pages.add(page);
        }
        vo.setRowsPerPage(ROWS_PER_PAGE);
        vo.setTotalPages(pageCount);
        vo.setPages(pages);
    }

    private List<String> blankRow(int width) {
        List<String> row = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            row.add("");
        }
        return row;
    }

    private static List<Map<String, String>> rowsOf(WmsKingdeePrintBill bill) {
        return bill.getRows() == null ? List.of() : bill.getRows();
    }

    private static Map<String, String> first(List<Map<String, String>> rows) {
        return rows.isEmpty() ? Collections.emptyMap() : rows.get(0);
    }

    private static String get(Map<String, String> row, String key) {
        return row == null ? null : row.get(key);
    }

    /** 名称+规格直接拼接（样单「产品名称及规格」列即此形态）。 */
    private static String joinNameSpec(String name, String spec) {
        return nvl(name) + nvl(spec);
    }

    /** 金蝶返回 FDate 形如 2026-11-09T00:00:00，打印只要日期。 */
    private static String dateOnly(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        int t = v.indexOf('T');
        return t > 0 ? v.substring(0, t) : (v.length() > 10 ? v.substring(0, 10) : v);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal num(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
