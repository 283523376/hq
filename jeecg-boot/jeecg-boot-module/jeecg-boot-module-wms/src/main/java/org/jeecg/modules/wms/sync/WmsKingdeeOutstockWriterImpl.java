package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
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
            // 基础资料一律用编码引用：真账套实测 `{"Id":...}` 不绑定，金蝶报「字段“客户”是必填项」
            model.put("FCustomerID", dict(order.getCustomerCode()));
            model.put("FDeliveryDeptID", dict(properties.getDeliveryDeptId()));
            model.put("FStockerID", dict(properties.getStockerId()));
            // SubHeadEntity（财务信息）是**单值**子实体：必须传字典，不能传数组。
            // 传 List 时金蝶报「字段Key:SubHeadEntity…格式错误，不能转换为字典类型」（2026-10-30 真账套实测）
            Map<String, Object> subHead = new LinkedHashMap<>();
            subHead.put("FSettleOrgID", dict(orgNumber));
            subHead.put("FSettleCurrID", dict(properties.getSettleCurrency()));
            model.put("SubHeadEntity", subHead);

            // 关联开单前置（发货通知单来源）：查通知单分录 + 草稿查重。
            // 金蝶 Push 通道在下推时要填必填字段而 WebAPI 无注入口，只能走 Save-with-Link
            // （与成品入库直开 PRD_INSTOCK↔PRD_MO 同一套路，2026-10-30 真账套实测）。
            String noticeNo = order.getSourceBillNo();
            boolean linked = oConvertUtils.isNotEmpty(noticeNo);
            Map<String, NoticeEntry> noticeEntries = null;
            if (linked) {
                WmsOrderSyncResult precheck = prepareLinked(order, noticeNo);
                if (precheck != null) {
                    return precheck;
                }
                noticeEntries = loadNoticeEntries(noticeNo);
                if (noticeEntries == null) {
                    return WmsOrderSyncResult.fail("发货通知单[" + noticeNo + "]分录查询失败，无法关联开单");
                }
                WmsOrderSyncResult draftCheck = rejectExistingDraft(order, noticeNo);
                if (draftCheck != null) {
                    return draftCheck;
                }
            }

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
                BigDecimal qty = nvl(detail.getActualQty());
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
                if (linked) {
                    NoticeEntry hit = noticeEntries.get(kingdeeCode);
                    if (hit == null) {
                        return WmsOrderSyncResult.fail("发货通知单[" + noticeNo + "]中没有物料[" + kingdeeCode
                                + "]的可出库分录，请核对该通知单是否已出库或物料是否一致");
                    }
                    if (qty.compareTo(hit.remainOutQty) > 0) {
                        return WmsOrderSyncResult.fail("发货通知单[" + noticeNo + "]物料[" + kingdeeCode
                                + "]剩余可出库 " + strip(hit.remainOutQty) + " 少于本次出库 " + strip(qty)
                                + "（可能已被其它出库单占用，请刷新后重试）");
                    }
                    // 来源字段必须在业务字段之前（金蝶保存顺序要求）
                    entry.put("FSrcBillType", WmsConstants.KINGDEE_FORM_DELIVERY_NOTICE);
                    entry.put("FSrcInterId", hit.billId);
                    entry.put("FSrcBillNo", noticeNo.trim());
                    entry.put("FSrcEntryId", hit.entryId);
                    entry.put("FStockStatusID", dict(WmsConstants.STOCK_STATUS_AVAILABLE));
                    entry.put("FQty", qty);
                    entry.put("FRealQty", qty);
                    entry.put("FEntity_Link", List.of(linkOf(hit)));
                } else {
                    entry.put("FRealQty", qty);
                }
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

    /** 通知单分录（按金蝶物料编码索引）：关联开单要带上来源单据与分录内码。 */
    private static final class NoticeEntry {
        private final long billId;
        private final int entryId;
        private final BigDecimal remainOutQty;

        NoticeEntry(long billId, int entryId, BigDecimal remainOutQty) {
            this.billId = billId;
            this.entryId = entryId;
            this.remainOutQty = remainOutQty;
        }
    }

    /**
     * 关联开单前置校验：必须配了发货部门/仓管员/仓库且客户能解析，否则金蝶保存时才发现，
     * 白跑一次查询。返回 null 表示通过。
     */
    private WmsOrderSyncResult prepareLinked(WmsOutboundOrder order, String noticeNo) {
        if (oConvertUtils.isEmpty(properties.getDeliveryDeptId())
                || oConvertUtils.isEmpty(properties.getStockerId())) {
            return WmsOrderSyncResult.fail("关联开单缺少发货部门/仓管员配置（delivery-dept-id / stocker-id）");
        }
        if (lookup.customerId(order.getCustomerCode(), properties.getSaleOrgNumber()) == null) {
            return WmsOrderSyncResult.fail("金蝶未找到客户编码：" + order.getCustomerCode());
        }
        if (oConvertUtils.isEmpty(noticeNo.trim())) {
            return WmsOrderSyncResult.fail("发货通知单号为空，无法关联开单");
        }
        return null;
    }

    /** 查通知单分录并按金蝶物料编码索引；查询失败返回 null（调用方给可读错误）。 */
    private Map<String, NoticeEntry> loadNoticeEntries(String noticeNo) {
        WmsKingdeeResult result = formClient.executeBillQuery(WmsConstants.KINGDEE_FORM_DELIVERY_NOTICE,
                "FID,FEntity_FEntryID,FMaterialID.FNumber,FRemainOutQty",
                "FBillNo='" + noticeNo.trim().replace("'", "''") + "'", 2000);
        if (!result.isSuccess()) {
            log.warn("发货通知单[{}]查询失败：{}", noticeNo, result.getMessage());
            return null;
        }
        Map<String, NoticeEntry> byMaterial = new LinkedHashMap<>();
        for (List<Object> row : result.getRows()) {
            if (row == null || row.size() < 4) {
                continue;
            }
            String materialCode = str(row.get(2));
            if (oConvertUtils.isEmpty(materialCode)) {
                continue;
            }
            byMaterial.putIfAbsent(materialCode,
                    new NoticeEntry(toLong(row.get(0)), toInt(row.get(1)), toDecimal(row.get(3))));
        }
        return byMaterial;
    }

    /**
     * 草稿查重：同一通知单已有未审核（A/B）的销售出库单时不再开单。
     *
     * <p>草稿期间通知单剩余可出库量**不减**（审核才核销，2026-10-30 实测），
     * 不查这一下就会重复开单。返回 null 表示无冲突。
     */
    private WmsOrderSyncResult rejectExistingDraft(WmsOutboundOrder order, String noticeNo) {
        WmsKingdeeResult drafts = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                "FSrcBillNo='" + noticeNo.trim().replace("'", "''")
                        + "' and FDocumentStatus in ('A','B')", 50);
        if (!drafts.isSuccess()) {
            // 查不到不等于没有：宁可拦住也不重复开单
            return WmsOrderSyncResult.fail("发货通知单[" + noticeNo + "]的关联出库单查询失败，"
                    + "为避免重复开单本次不开单：" + drafts.getMessage());
        }
        for (List<Object> row : drafts.getRows()) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String existing = str(row.get(0));
            if (oConvertUtils.isNotEmpty(existing) && !existing.equals(order.getErpBillNo())) {
                return WmsOrderSyncResult.fail("发货通知单[" + noticeNo + "]已存在未审核的出库单 "
                        + existing + "，请先在金蝶处理（审核或删除）后再重试");
            }
        }
        return null;
    }

    private Map<String, Object> linkOf(NoticeEntry entry) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("FEntity_Link_FRuleId", WmsConstants.LINK_RULE_DELIVERY_OUTSTOCK);
        link.put("FEntity_Link_FSTableName", WmsConstants.LINK_TABLE_NOTICE_ENTRY);
        link.put("FEntity_Link_FSBillId", String.valueOf(entry.billId));
        link.put("FEntity_Link_FSId", String.valueOf(entry.entryId));
        return link;
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null || oConvertUtils.isEmpty(String.valueOf(value))) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
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

    private String strip(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
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
