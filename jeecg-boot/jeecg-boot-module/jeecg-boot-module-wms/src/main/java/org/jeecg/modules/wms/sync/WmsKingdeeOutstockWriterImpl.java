package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
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
 * <p>单号以金蝶为准（2026-10-28 改造）：不传 FBillNo，保存后回存
 * {@code wms_outbound_order.erp_bill_no}，幂等按 erp_bill_no 跟进（旧 WMS 单号做兼容查询）；
 * 发货部门/仓管员必须配置，缺失时明确失败。
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
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsMaterialCodeResolver materialResolver;

    public WmsKingdeeOutstockWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                        WmsKingdeeLookupSupport lookup, WmsOutboundOrderMapper outboundMapper,
                                        WmsMaterialCodeResolver materialResolver) {
        this.formClient = formClient;
        this.properties = properties;
        this.lookup = lookup;
        this.outboundMapper = outboundMapper;
        this.materialResolver = materialResolver;
    }

    @Override
    public WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        if (!properties.isEnabled()) {
            return WmsOrderSyncResult.okMock();
        }
        String billNo = order.getOutboundNo();
        try {
            // 1) 幂等：本地已回存金蝶单号 → 按它跟进状态
            if (oConvertUtils.isNotEmpty(order.getErpBillNo())) {
                return followUp(order, order.getErpBillNo());
            }
            // 1.1) 兼容查询：改造前按 WMS 单号开过单，接住存量（查到即回存单号），避免重复开单
            WmsKingdeeResult legacy = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                    "FBillNo='" + billNo.replace("'", "''") + "'", 1);
            if (!legacy.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶销售出库单查询失败：" + legacy.getMessage());
            }
            if (!legacy.getRows().isEmpty()) {
                String status = str(legacy.getRows().get(0).get(1));
                if ("C".equals(status) || "B".equals(status)) {
                    persistErpBillNo(order.getId(), billNo);
                    return WmsOrderSyncResult.ok();
                }
                WmsOrderSyncResult legacyResult = submit(billNo);
                if (legacyResult.isSuccess()) {
                    persistErpBillNo(order.getId(), billNo);
                }
                return legacyResult;
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

            // 单号以金蝶为准：不传 FBillNo，由金蝶按编码规则生成
            Map<String, Object> model = new LinkedHashMap<>();
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
                String kingdeeCode = materialResolver.resolveKingdeeCode(detail.getMaterialCode());
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
            String erpBillNo = saved.getNumber();
            if (oConvertUtils.isEmpty(erpBillNo)) {
                return WmsOrderSyncResult.fail("金蝶销售出库保存后未返回单号（WMS单号 " + billNo + "）");
            }
            WmsOrderSyncResult result = submit(erpBillNo);
            if (result.isSuccess()) {
                // 回存失败必须抛出：没有 erp_bill_no，重试会重复开单
                persistErpBillNo(order.getId(), erpBillNo);
            }
            return result;
        } catch (Exception e) {
            log.warn("销售出库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    /** 本地已有金蝶单号：按状态跟进——B/C 视为完成，其余补提交。 */
    private WmsOrderSyncResult followUp(WmsOutboundOrder order, String erpBillNo) {
        WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                "FBillNo='" + erpBillNo.replace("'", "''") + "'", 1);
        if (!existing.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶销售出库单查询失败：" + existing.getMessage());
        }
        if (existing.getRows().isEmpty()) {
            return WmsOrderSyncResult.fail("金蝶销售出库单不存在（本地单号 " + erpBillNo
                    + "，可能被人工删除），请核对后清理 erp_bill_no");
        }
        String status = str(existing.getRows().get(0).get(1));
        if ("C".equals(status) || "B".equals(status)) {
            return WmsOrderSyncResult.ok();
        }
        return submit(erpBillNo);
    }

    /** 回存金蝶单号（只更新 erp_bill_no 列）。失败抛出 → 本次同步失败，防重试重复开单。 */
    private void persistErpBillNo(String orderId, String erpBillNo) {
        WmsOutboundOrder patch = new WmsOutboundOrder();
        patch.setId(orderId);
        patch.setErpBillNo(erpBillNo);
        outboundMapper.updateById(patch);
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶销售出库提交失败：" + submitted.getMessage());
        }
        log.info("销售出库回写金蝶成功：ERP单号={}", number);
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
