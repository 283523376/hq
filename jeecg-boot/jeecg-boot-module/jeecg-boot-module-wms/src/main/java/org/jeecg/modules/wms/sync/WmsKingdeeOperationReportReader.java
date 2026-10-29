package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.vo.WmsOperationReportRowVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 金蝶工序汇报单（`SFC_OperationReport`，单号 `GXHB*`）读取。
 *
 * <p>工序汇报是**半成品**的来源（用户口径：半成品是工序汇报产生的库存，成品是完工入库）。
 * 页面路径：智慧车间MES → 智慧车间 → 车间报工执行。
 *
 * <p>查询字段必须带 `F` 前缀（`FWorkShopID.FName`、`FMoNumber`…），不带会报
 * 「元数据中标识为 xxx 的字段不存在」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
public class WmsKingdeeOperationReportReader {

    static final String FORM_ID = "SFC_OperationReport";

    /** 下标与 {@link #FIELD_KEYS} 一一对应。 */
    static final String FIELD_KEYS = String.join(",",
            "FBillNo", "FDate", "FPrdOrgId.FNumber", "FWorkShopID.FName",
            "FMaterialId.FNumber", "FMaterialId.FName", "FMoNumber", "FLot_Text",
            // 工序序列是 FSeqNumber（FSeq 在金蝶元数据里不存在，2026-10-28 实测）
            "FQuaQty", "FSeqNumber", "FOperNumber", "FStockId.FNumber", "FStockId.FName");

    /**
     * 稳定排序：FModifyDate 定增量顺序，其余三列保证同一时间戳下的全序，翻页不会重复或漏行。
     */
    static final String ORDER_STRING = "FModifyDate ASC,FBillNo ASC,FSeqNumber ASC,FOperNumber ASC";

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 200;

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeOperationReportReader(WmsKingdeeFormClient formClient,
                                           WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    /**
     * 拉取已审核的工序汇报分录。
     *
     * @param startDate 起始日期（含），yyyy-MM-dd
     * @param endDate   结束日期（含），yyyy-MM-dd
     * @param maxRows   最多拉取行数（防一次拉爆）
     */
    /**
     * 把 {@code yyyy-MM-dd} 补成 datetime。
     *
     * <p>{@code FModifyDate} 是 datetime 字段：只给日期时，`<= '2026-10-29'` 等价于当天 00:00:00，
     * 整天都会被漏掉。统一补时间最稳。
     */
    static String expandBound(String value, String timeIfMissing) {
        String v = value.trim();
        return v.length() <= 10 ? v + " " + timeIfMissing : v;
    }

    public List<WmsOperationReportRowVO> fetch(String startDate, String endDate, int maxRows) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("金蝶同步总开关 wms.sync.erp.kingdee.enabled=false");
        }
        String org = properties.getWorkOrderOrgNumber();
        StringBuilder filter = new StringBuilder("FDocumentStatus='C'");
        if (org != null && !org.trim().isEmpty()) {
            filter.append(" AND FPrdOrgId.FNumber='").append(org.trim().replace("'", "''")).append("'");
        }
        // 按 FModifyDate（最后修改时间）过滤，不是 FDate（业务日期）：
        // 用业务日期会让「迟审/补录」的单据在窗口滚过后永久丢失（2026-10-29 口径）。
        if (startDate != null && !startDate.trim().isEmpty()) {
            filter.append(" AND FModifyDate>='").append(expandBound(startDate, "00:00:00")).append("'");
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            filter.append(" AND FModifyDate<='").append(expandBound(endDate, "23:59:59")).append("'");
        }

        List<WmsOperationReportRowVO> all = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && all.size() < maxRows; page++) {
            int size = Math.min(PAGE_SIZE, maxRows - all.size());
            WmsKingdeeResult result = formClient.executeBillQuery(
                    FORM_ID, FIELD_KEYS, filter.toString(), size, page * PAGE_SIZE, ORDER_STRING);
            if (!result.isSuccess()) {
                throw new RuntimeException("金蝶工序汇报查询失败：" + result.getMessage());
            }
            List<List<Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            for (List<Object> row : rows) {
                WmsOperationReportRowVO vo = toRow(row);
                if (vo != null) {
                    all.add(vo);
                }
            }
            if (rows.size() < size) {
                return all;
            }
        }
        return all;
    }

    private WmsOperationReportRowVO toRow(List<Object> row) {
        if (row == null || row.size() < 11) {
            return null;
        }
        WmsOperationReportRowVO vo = new WmsOperationReportRowVO();
        vo.setBillNo(text(row.get(0)));
        vo.setBillDate(text(row.get(1)));
        vo.setOrgNumber(text(row.get(2)));
        vo.setWorkshopName(text(row.get(3)));
        vo.setMaterialCode(text(row.get(4)));
        vo.setMaterialName(text(row.get(5)));
        vo.setMoNumber(text(row.get(6)));
        vo.setLot(text(row.get(7)));
        vo.setQualifiedQty(decimal(row.get(8)));
        vo.setSeq(text(row.get(9)));
        vo.setOperNumber(text(row.get(10)));
        vo.setStockNumber(row.size() > 11 ? text(row.get(11)) : null);
        vo.setStockName(row.size() > 12 ? text(row.get(12)) : null);
        return vo.getBillNo().isEmpty() ? null : vo;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
