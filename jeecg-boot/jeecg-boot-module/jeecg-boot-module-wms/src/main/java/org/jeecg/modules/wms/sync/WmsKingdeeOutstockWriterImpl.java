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
 * @Description: CK-002 销售出库 → 金蝶 SAL_OUTSTOCK（Save + Submit）。
 *
 * <p>幂等键 FBillNo = WMS 出库单号；发货部门/仓管员必须配置，缺失时明确失败。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsKingdeeOutstockWriterImpl implements IWmsKingdeeOutstockWriter {

    private static final String FORM_ID = "SAL_OUTSTOCK";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsKingdeeLookupSupport lookup;

    public WmsKingdeeOutstockWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
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
        String billNo = order.getOutboundNo();
        try {
            WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                    "FBillNo='" + billNo.replace("'", "''") + "'", 1);
            if (!existing.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶销售出库单查询失败：" + existing.getMessage());
            }
            if (!existing.getRows().isEmpty()) {
                String status = str(existing.getRows().get(0).get(1));
                if ("C".equals(status) || "B".equals(status)) {
                    return WmsOrderSyncResult.ok();
                }
                return submit(billNo);
            }

            if (oConvertUtils.isEmpty(properties.getDeliveryDeptId())) {
                return WmsOrderSyncResult.fail("未配置销售出库发货部门（delivery-dept-id）");
            }
            if (oConvertUtils.isEmpty(properties.getStockerId())) {
                return WmsOrderSyncResult.fail("未配置销售出库仓管员（stocker-id）");
            }
            Long customerId = lookup.customerId(order.getCustomerCode(), properties.getSaleOrgNumber());
            if (customerId == null) {
                return WmsOrderSyncResult.fail("金蝶未找到客户编码：" + order.getCustomerCode());
            }
            String orgNumber = properties.getSaleOrgNumber();
            String stockCode = lookup.stockCode(orgNumber, "agile");
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code.agile，组织 " + orgNumber + "）");
            }
            Long stockLocId = lookup.stockLocId(stockCode);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillNo", billNo);
            model.put("FDate", formatDate(new Date()));
            model.put("FSaleOrgId", dict(orgNumber));
            model.put("FStockOrgId", dict(orgNumber));
            model.put("FBillTypeID", dict(properties.getOutStockBillType()));
            model.put("FCustomerID", idDict(customerId));
            model.put("FDeliveryDeptID", dict(properties.getDeliveryDeptId()));
            model.put("FStockerID", dict(properties.getStockerId()));
            model.put("SubHeadEntity", List.of(Map.of(
                    "FSettleOrgID", dict(orgNumber),
                    "FSettleCurrID", dict(properties.getSettleCurrency()))));

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
                entry.put("FMaterialID", dict(kingdeeCode));
                entry.put("FUnitID", dict(unitNumber));
                entry.put("FOwnerTypeID", properties.getOwnerType());
                entry.put("FOwnerID", dict(orgNumber));
                entry.put("FStockId", dict(stockCode));
                if (stockLocId != null) {
                    entry.put("FStockLocId", stockLocId);
                }
                if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
                    entry.put("FLot", dict(detail.getBatchNo()));
                }
                entry.put("FRealQty", nvl(detail.getActualQty()));
                entries.add(entry);
            }
            model.put("FEntity", entries);

            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶销售出库保存失败：" + saved.getMessage());
            }
            return submit(oConvertUtils.isEmpty(saved.getNumber()) ? billNo : saved.getNumber());
        } catch (Exception e) {
            log.warn("销售出库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶销售出库提交失败：" + submitted.getMessage());
        }
        log.info("销售出库回写金蝶成功：FBillNo={}", number);
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
