package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶销售订单读取实现（`SAL_SaleOrder`）。
 *
 * <p>实测字段（2026-10-22，测试账套）：
 * 表头 `FBillHead`：`FBillNo/FDocumentStatus/FDate/FCustId/FSaleOrgId/FNote`；
 * 分录 `FSaleOrderEntry`：`FMaterialId/FUnitID/FQty/FDeliveryDate`。
 * 查询按 `FBillNo` 聚合为「头 + 行」，供 WMS 写快照。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsKingdeeSaleOrderReaderImpl implements IWmsKingdeeSaleOrderReader {

    private static final String FORM_ID = "SAL_SaleOrder";

    /** 字段键与下标一一对应，改动时同步 parseRow。 */
    private static final String FIELD_KEYS =
            "FBillNo,FDocumentStatus,FDate,FCustId.FNumber,FCustId.FName,FSaleOrgId.FNumber,FNote,"
                    + "FMaterialId.FNumber,FMaterialId.FName,FQty,FDeliveryDate,FUnitID.FNumber";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeSaleOrderReaderImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public WmsKingdeeSaleOrderQueryResult queryByBillNo(String billNo) {
        if (!properties.isEnabled()) {
            return WmsKingdeeSaleOrderQueryResult.mock();
        }
        if (oConvertUtils.isEmpty(billNo)) {
            return WmsKingdeeSaleOrderQueryResult.fail("销售订单号不能为空");
        }
        return query("FBillNo='" + billNo.trim().replace("'", "''") + "'", 1);
    }

    @Override
    public WmsKingdeeSaleOrderQueryResult queryApproved(Date begin, Date end, int limit) {
        if (!properties.isEnabled()) {
            return WmsKingdeeSaleOrderQueryResult.mock();
        }
        String beginText = formatDate(begin == null ? new Date(0) : begin);
        String endText = formatDate(end == null ? new Date() : end);
        String filter = "FDocumentStatus='" + WmsConstants.SALES_ORDER_STATUS_APPROVED + "'"
                + " AND FDate>='" + beginText + "' AND FDate<='" + endText + " 23:59:59'";
        return query(filter, limit <= 0 ? WmsConstants.SALES_ORDER_SYNC_LIMIT : limit);
    }

    private WmsKingdeeSaleOrderQueryResult query(String filter, int limit) {
        WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, FIELD_KEYS, filter, limit);
        if (!result.isSuccess()) {
            return WmsKingdeeSaleOrderQueryResult.fail("金蝶销售订单查询失败：" + result.getMessage());
        }
        // 按单号聚合（金蝶返回的是「头字段 × 分录」的笛卡尔行）
        Map<String, WmsKingdeeSaleOrder> orders = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String billNo = str(row, 0);
            if (oConvertUtils.isEmpty(billNo)) {
                continue;
            }
            WmsKingdeeSaleOrder order = orders.computeIfAbsent(billNo, key -> {
                WmsKingdeeSaleOrder created = new WmsKingdeeSaleOrder();
                created.setSalesOrderNo(key);
                created.setDocumentStatus(str(row, 1));
                created.setOrderDate(parseDate(str(row, 2)));
                created.setCustomerCode(str(row, 3));
                created.setCustomerName(str(row, 4));
                created.setSaleOrgNumber(str(row, 5));
                created.setRemark(str(row, 6));
                return created;
            });
            WmsKingdeeSaleOrderLine line = new WmsKingdeeSaleOrderLine();
            line.setMaterialCode(str(row, 7));
            line.setMaterialName(str(row, 8));
            line.setOrderQty(toDecimal(row, 9));
            line.setDeliveryDate(parseDate(str(row, 10)));
            line.setUnit(str(row, 11));
            if (oConvertUtils.isNotEmpty(line.getMaterialCode())) {
                order.getLines().add(line);
            }
        }
        return WmsKingdeeSaleOrderQueryResult.ok(new ArrayList<>(orders.values()));
    }

    private String str(List<Object> row, int index) {
        if (row.size() <= index) {
            return null;
        }
        Object value = row.get(index);
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal toDecimal(List<Object> row, int index) {
        if (row.size() <= index || row.get(index) == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(row.get(index)));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 金蝶返回格式：`2026-10-20T00:00:00` 或 `2026-10-20`。 */
    private Date parseDate(String text) {
        if (oConvertUtils.isEmpty(text)) {
            return null;
        }
        String value = text.trim();
        String pattern = value.contains("T") ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd";
        String trimmed = value.length() > 19 ? value.substring(0, 19) : value;
        try {
            return new SimpleDateFormat(pattern).parse(trimmed);
        } catch (ParseException e) {
            log.warn("金蝶日期解析失败：{}", text);
            return null;
        }
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date);
    }
}
