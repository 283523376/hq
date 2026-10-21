package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: CK-002 销售订单 → 金蝶 SAL_SaleOrder（Save + Submit）。
 *
 * <p>幂等键 FBillNo = WMS 销售订单号；客户按编码解析，找不到明确失败。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsKingdeeSaleOrderWriterImpl implements IWmsKingdeeSaleOrderWriter {

    private static final String FORM_ID = "SAL_SaleOrder";
    private static final String RESERVE_TYPE = "1";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsKingdeeLookupSupport lookup;

    public WmsKingdeeSaleOrderWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                         WmsKingdeeLookupSupport lookup) {
        this.formClient = formClient;
        this.properties = properties;
        this.lookup = lookup;
    }

    @Override
    public WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        if (!properties.isEnabled()) {
            return WmsOrderSyncResult.okMock();
        }
        String billNo = order.getSalesOrderNo();
        try {
            WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                    "FBillNo='" + billNo.replace("'", "''") + "'", 1);
            if (!existing.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶销售订单查询失败：" + existing.getMessage());
            }
            if (!existing.getRows().isEmpty()) {
                String status = str(existing.getRows().get(0).get(1));
                if ("C".equals(status) || "B".equals(status)) {
                    return WmsOrderSyncResult.ok();
                }
                return submit(billNo);
            }

            if (oConvertUtils.isEmpty(properties.getSalerId())) {
                return WmsOrderSyncResult.fail("未配置默认销售员（saler-id）");
            }
            Long customerId = lookup.customerId(order.getCustomerCode(), properties.getSaleOrgNumber());
            if (customerId == null) {
                return WmsOrderSyncResult.fail("金蝶未找到客户编码：" + order.getCustomerCode());
            }
            String orgNumber = properties.getSaleOrgNumber();
            String deliveryDate = formatDate(order.getDeliveryDate());

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillNo", billNo);
            model.put("FDate", formatDate(new Date()));
            model.put("FSaleOrgId", dict(orgNumber));
            model.put("FBillTypeID", dict(properties.getSaleOrderBillType()));
            model.put("FCustId", idDict(customerId));
            model.put("FSalerId", dict(properties.getSalerId()));

            List<Map<String, Object>> entries = new ArrayList<>();
            for (WmsOutboundDetail detail : details) {
                String kingdeeCode = properties.getMaterialMap()
                        .getOrDefault(detail.getMaterialCode(), detail.getMaterialCode());
                List<Object> material = lookup.material(kingdeeCode, orgNumber);
                if (material == null || material.isEmpty()) {
                    return WmsOrderSyncResult.fail("金蝶未找到物料编码：" + kingdeeCode);
                }
                Object unitId = material.size() > 3 ? material.get(3) : null;
                String unitNumber = lookup.unitNumber(unitId);
                if (oConvertUtils.isEmpty(unitNumber)) {
                    return WmsOrderSyncResult.fail("金蝶未找到单位编码（FUnitId=" + unitId + "）");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("FMaterialId", dict(kingdeeCode));
                entry.put("FUnitID", dict(unitNumber));
                entry.put("FPriceUnitId", dict(unitNumber));
                entry.put("FStockUnitID", dict(unitNumber));
                entry.put("FOUTLMTUNIT", dict(unitNumber));
                entry.put("FSettleOrgIds", dict(orgNumber));
                entry.put("FDeliveryDate", deliveryDate);
                entry.put("FReserveType", RESERVE_TYPE);
                entry.put("FQty", nvl(detail.getRequiredQty()));
                entries.add(entry);
            }
            model.put("FSaleOrderEntry", entries);
            model.put("FSaleOrderFinance", List.of(Map.of(
                    "FSettleCurrId", dict(properties.getSettleCurrency()))));

            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶销售订单保存失败：" + saved.getMessage());
            }
            return submit(oConvertUtils.isEmpty(saved.getNumber()) ? billNo : saved.getNumber());
        } catch (Exception e) {
            log.warn("销售订单[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶销售订单提交失败：" + submitted.getMessage());
        }
        log.info("销售订单回写金蝶成功：FBillNo={}", number);
        return WmsOrderSyncResult.ok();
    }

    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    private Map<String, Object> idDict(Object id) {
        return Map.of("Id", id);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
