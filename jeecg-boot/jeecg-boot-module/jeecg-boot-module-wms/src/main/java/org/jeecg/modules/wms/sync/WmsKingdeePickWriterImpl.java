package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: CK-001 生产领料 → 金蝶 PRD_PickMtrl（Save + Submit）。
 *
 * <p>组织跟随生产订单；幂等键 FBillNo = WMS 出库单号。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsKingdeePickWriterImpl implements IWmsKingdeePickWriter {

    private static final String FORM_ID = "PRD_PickMtrl";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final WmsKingdeeLookupSupport lookup;

    public WmsKingdeePickWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
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
                return WmsOrderSyncResult.fail("金蝶生产领料单查询失败：" + existing.getMessage());
            }
            if (!existing.getRows().isEmpty()) {
                String status = str(existing.getRows().get(0).get(1));
                if ("C".equals(status) || "B".equals(status)) {
                    return WmsOrderSyncResult.ok();
                }
                return submit(billNo);
            }

            String orgNumber = lookup.orgNumberForWorkOrder(order.getWorkOrderNo());
            if (oConvertUtils.isEmpty(orgNumber)) {
                return WmsOrderSyncResult.fail("金蝶未找到生产订单：" + order.getWorkOrderNo());
            }
            String stockCode = lookup.stockCode(orgNumber, "stereo");
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code.stereo，组织 " + orgNumber + "）");
            }
            Long stockLocId = lookup.stockLocId(stockCode);

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillNo", billNo);
            model.put("FDate", formatDate());
            model.put("FPrdOrgId", dict(orgNumber));
            model.put("FStockOrgId", dict(orgNumber));
            model.put("FBillType", dict(properties.getPickBillType()));

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
                entry.put("FIsNew", false);
                entry.put("FMoBillNo", order.getWorkOrderNo());
                entry.put("FMaterialId", dict(kingdeeCode));
                entry.put("FUnitID", dict(unitNumber));
                entry.put("FBaseUnitId", dict(unitNumber));
                entry.put("FStockUnitId", dict(unitNumber));
                entry.put("FOwnerTypeId", properties.getOwnerType());
                entry.put("FOwnerId", dict(orgNumber));
                entry.put("FKeeperTypeId", properties.getKeeperType());
                entry.put("FKeeperId", dict(orgNumber));
                entry.put("FParentOwnerTypeId", properties.getOwnerType());
                entry.put("FParentOwnerId", dict(orgNumber));
                entry.put("FStockId", dict(stockCode));
                if (stockLocId != null) {
                    entry.put("FStockLocId", stockLocId);
                }
                entry.put("FStockStatusId", dict(properties.getStockStatusId()));
                if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
                    entry.put("FLot", dict(detail.getBatchNo()));
                }
                entry.put("FRealQty", nvl(detail.getActualQty()));
                entries.add(entry);
            }
            model.put("FEntity", entries);
            model.put("FSerialSubEntity", List.of(Map.of("FIsAppInspect", false)));

            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶生产领料保存失败：" + saved.getMessage());
            }
            return submit(oConvertUtils.isEmpty(saved.getNumber()) ? billNo : saved.getNumber());
        } catch (Exception e) {
            log.warn("领料出库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶生产领料提交失败：" + submitted.getMessage());
        }
        log.info("生产领料回写金蝶成功：FBillNo={}", number);
        return WmsOrderSyncResult.ok();
    }

    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String formatDate() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
