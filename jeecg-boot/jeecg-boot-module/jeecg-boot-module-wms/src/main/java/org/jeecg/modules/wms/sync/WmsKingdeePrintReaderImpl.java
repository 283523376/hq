package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 金蝶单据打印只读 Reader（DY-001）。
 *
 * <p><b>字段键为测试账套（6a9aaaadaf7ee6）2026-11-09 实测收敛结果</b>
 * （探测脚本 {@code tmp_files/print-field-probe*.py}，金蝶报错逐字段剔除/替换）：
 * <ul>
 *   <li>委外领料「订单编号/实发数量」：测试账套元数据无 FOrderNo/FOrderBillNo/FQty/
 *       FRealQty/FBaseQty/FSRCQTY/FSTOCKQTY——订单编号列留空、实发数量暂取 FAppQty（应发数量）；
 *       生产账套若有更准字段，改 FIELD_KEYS 一行即可；</li>
 *   <li>销售出库「客户订单号」：测试账套无该字段——留空；客户字段是 FCustomerId（非 FCustId）、
 *       数量是 FRealQty（非 FQty）；</li>
 *   <li>销售退货：客户字段是 FSettleCustId、数量是 FRealQty、无 FEntryNote——备注列留空。</li>
 * </ul>
 * 过滤器一律 {@code FBillNo='…' AND FDocumentStatus='C'}——客户 2026-11-09 拍板：
 * 未审核单据不能打印；查无（含未审核被过滤）返回 empty，文案由上层合并给「不存在或未审核」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Slf4j
@Service
public class WmsKingdeePrintReaderImpl implements IWmsKingdeePrintReader {

    /** 其他出库单（盘亏单 QTCK… / 其他出库单同对象）。 */
    public static final String FORM_STK_MIS_DELIVERY = "STK_MisDelivery";
    /** 其他入库单（盘盈单 QTRK… / 其他入库单同对象）。 */
    public static final String FORM_STK_MISCELLANEOUS = "STK_MISCELLANEOUS";
    /** 委外领料单。 */
    public static final String FORM_SUB_PICK_MTRL = "SUB_PickMtrl";
    /** 销售出库单。 */
    public static final String FORM_SAL_OUTSTOCK = "SAL_OUTSTOCK";
    /** 销售退货单。 */
    public static final String FORM_SAL_RETURNSTOCK = "SAL_RETURNSTOCK";

    /** 单据类型 → formId。 */
    private static final Map<String, String> FORM_ID_BY_TYPE = Map.of(
            WmsConstants.PRINT_BILL_LOSS, FORM_STK_MIS_DELIVERY,
            WmsConstants.PRINT_BILL_MISC_DELIVERY, FORM_STK_MIS_DELIVERY,
            WmsConstants.PRINT_BILL_PROFIT, FORM_STK_MISCELLANEOUS,
            WmsConstants.PRINT_BILL_MISC_INSTOCK, FORM_STK_MISCELLANEOUS,
            WmsConstants.PRINT_BILL_SUB_PICK, FORM_SUB_PICK_MTRL,
            WmsConstants.PRINT_BILL_SALES_OUTSTOCK, FORM_SAL_OUTSTOCK,
            WmsConstants.PRINT_BILL_SALES_RETURN, FORM_SAL_RETURNSTOCK);

    /** 单据类型 → 查询字段键（实测口径见类注释）。 */
    private static final Map<String, String> FIELD_KEYS_BY_TYPE = Map.of(
            WmsConstants.PRINT_BILL_LOSS, "FBillNo,FDocumentStatus,FDate,FDeptId.FName,FBillTypeID.FName,"
                    + "FApproverId.FName,FKeeperId.FName,FPickerId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FQty,FEntryNote,FLot.FNumber,FStockId.FName,FPrice,FAmount",
            WmsConstants.PRINT_BILL_MISC_DELIVERY, "FBillNo,FDocumentStatus,FDate,FDeptId.FName,FBillTypeID.FName,"
                    + "FApproverId.FName,FKeeperId.FName,FPickerId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FQty,FEntryNote,FLot.FNumber,FStockId.FName,FPrice,FAmount",
            WmsConstants.PRINT_BILL_PROFIT, "FBillNo,FDocumentStatus,FDate,FDeptId.FName,"
                    + "FApproverId.FName,FKeeperId.FName,FCreatorId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FQty,FLot.FNumber,FStockId.FName,FEntryNote,FPrice,FAmount",
            WmsConstants.PRINT_BILL_MISC_INSTOCK, "FBillNo,FDocumentStatus,FDate,FDeptId.FName,"
                    + "FApproverId.FName,FKeeperId.FName,FCreatorId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FQty,FLot.FNumber,FStockId.FName,FEntryNote,FPrice,FAmount",
            WmsConstants.PRINT_BILL_SUB_PICK, "FBillNo,FDocumentStatus,FDate,FSupplierId.FName,"
                    + "FApproverId.FName,FCreatorId.FName,FAppQty,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FStockId.FName",
            WmsConstants.PRINT_BILL_SALES_OUTSTOCK, "FBillNo,FDocumentStatus,FDate,FBillTypeID.FName,"
                    + "FCustomerId.FNumber,FCustomerId.FName,FApproverId.FName,FKeeperId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FRealQty,FPrice,FAmount,FEntryNote,FSrcBillNo",
            WmsConstants.PRINT_BILL_SALES_RETURN, "FBillNo,FDocumentStatus,FDate,FSettleCustId.FName,"
                    + "FApproverId.FName,FKeeperId.FName,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FMaterialId.FSpecification,"
                    + "FUnitId.FName,FRealQty");

    /** 一张单的分录上限（打印场景单据分录远小于此）。 */
    private static final int QUERY_LIMIT = 500;

    /** 列表查询字段（工作台只用单号与日期）。 */
    private static final String LIST_FIELD_KEYS = "FBillNo,FDate,FDocumentStatus";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeePrintReaderImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public Optional<WmsKingdeePrintBill> queryApproved(String billType, String erpBillNo) {
        String type = billType == null ? "" : billType.trim();
        String formId = FORM_ID_BY_TYPE.get(type);
        if (formId == null) {
            throw new IllegalArgumentException("不支持的打印单据类型：" + type);
        }
        String fieldKeys = FIELD_KEYS_BY_TYPE.get(type);
        String no = erpBillNo == null ? "" : erpBillNo.trim();
        if (no.isEmpty()) {
            throw new IllegalArgumentException("打印单号不能为空");
        }
        String filter = "FBillNo='" + no.replace("'", "''") + "' AND FDocumentStatus='C'";
        WmsKingdeeResult result = formClient.executeBillQuery(formId, fieldKeys, filter, QUERY_LIMIT);
        if (!result.isSuccess()) {
            throw new RuntimeException("金蝶单据查询失败：" + result.getMessage());
        }
        List<List<Object>> rawRows = result.getRows();
        if (rawRows == null || rawRows.isEmpty()) {
            return Optional.empty();
        }
        String[] keys = fieldKeys.split(",");
        List<Map<String, String>> rows = new ArrayList<>(rawRows.size());
        String billNo = null;
        for (List<Object> raw : rawRows) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>(keys.length * 2);
            for (int i = 0; i < keys.length; i++) {
                Object value = i < raw.size() ? raw.get(i) : null;
                row.put(keys[i], value == null ? null : String.valueOf(value));
            }
            if (billNo == null) {
                billNo = row.get("FBillNo");
            }
            rows.add(row);
        }
        if (billNo == null || rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WmsKingdeePrintBill()
                .setBillType(type)
                .setFormId(formId)
                .setBillNo(billNo)
                .setRows(rows));
    }

    @Override
    public List<Map<String, String>> queryApprovedPage(String billType, String keyword,
                                                       int startRow, int limit) {
        String type = billType == null ? "" : billType.trim();
        String formId = FORM_ID_BY_TYPE.get(type);
        if (formId == null) {
            throw new IllegalArgumentException("不支持的打印单据类型：" + type);
        }
        String safeFilter = "FDocumentStatus='C'";
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            safeFilter += " AND FBillNo like '%" + kw.replace("'", "''") + "%'";
        }
        // 多取 1 条供上层判断 hasMore；金蝶翻页必须 TopRowCount=0 + Limit（项目既有坑）
        int safeLimit = Math.max(1, limit) + 1;
        // 翻页必须给稳定排序，否则顺序不定会让同一行在两页重复、另一些行永远取不到
        WmsKingdeeResult result = formClient.executeBillQuery(formId, LIST_FIELD_KEYS, safeFilter,
                safeLimit, Math.max(0, startRow), "FDate DESC,FBillNo DESC");
        if (!result.isSuccess()) {
            throw new RuntimeException("金蝶单据列表查询失败：" + result.getMessage());
        }
        List<Map<String, String>> rows = new ArrayList<>();
        if (result.getRows() != null) {
            for (List<Object> raw : result.getRows()) {
                if (raw == null || raw.isEmpty() || raw.get(0) == null) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("FBillNo", String.valueOf(raw.get(0)));
                row.put("FDate", raw.size() > 1 && raw.get(1) != null ? String.valueOf(raw.get(1)) : null);
                row.put("FDocumentStatus", raw.size() > 2 && raw.get(2) != null
                        ? String.valueOf(raw.get(2)) : null);
                rows.add(row);
            }
        }
        return rows;
    }
}
