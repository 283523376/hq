package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
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
 * <p><b>2026-10-27 真机校准</b>：本类此前只有单测、从未打过真机，按原报文**上线必然失败**
 * （金蝶只会回一句「分录"明细"是必填项」，不指名缺哪个字段）。按官方 Save 样例 + 逐项报错反推，
 * 补齐了下列必填项：
 * <ul>
 *   <li><b>{@code FParentMaterialId} 父项物料</b>——给哪个产品领料。最关键的一项，
 *       缺它金蝶只报「分录明细是必填项」</li>
 *   <li><b>{@code FPPBomBillNo} / {@code FPPBomEntryId}</b>——关联**已审核**的生产用料清单分录</li>
 *   <li><b>{@code FWorkShopId} / {@code FEntryWorkShopId}</b>——车间，单头与分录都要给（金蝶报「车间必录」）</li>
 *   <li><b>数量组五个字段</b>：FAppQty / FActualQty / FBaseActualQty / FStockActualQty /
 *       FBaseStockActualQty，必须齐全且一致（少 FBaseStockActualQty 会报「基本单位实发数量、
 *       主库存基本单位数量要一致」）</li>
 *   <li><b>{@code FSerialSubEntity} 必须放在分录里</b>——放单据头上会报 BillHead 不存在该属性</li>
 * </ul>
 *
 * <p><b>前置校验</b>（先给出可执行的中文原因，避免把金蝶的原始报错直接甩给用户）：
 * 生产订单已审核 → 用料清单存在且已审核 → 批号存在且可用 → 领料仓有可用库存。
 *
 * <p>组织跟随生产订单。单号以金蝶为准（2026-10-28 改造）：不传 FBillNo，保存后把金蝶单号
 * 回存 {@code wms_outbound_order.erp_bill_no}；幂等改为「erp_bill_no 有值按它查，
 * 无值先用旧 WMS 单号做一次兼容查询（接住改造前存量单），再走创建」。
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
    private final WmsOutboundOrderMapper outboundMapper;
    private final WmsMaterialCodeResolver materialResolver;
    private final WmsKingdeeBatchSupport batchSupport;

    public WmsKingdeePickWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                    WmsKingdeeLookupSupport lookup, WmsOutboundOrderMapper outboundMapper,
                                    WmsMaterialCodeResolver materialResolver, WmsKingdeeBatchSupport batchSupport) {
        this.formClient = formClient;
        this.properties = properties;
        this.lookup = lookup;
        this.outboundMapper = outboundMapper;
        this.materialResolver = materialResolver;
        this.batchSupport = batchSupport;
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
                return WmsOrderSyncResult.fail("金蝶生产领料单查询失败：" + legacy.getMessage());
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

            // 2) 生产订单：组织、车间、父项物料（必须已审核，PPBOM 才会自动审核）
            WmsKingdeeResult moResult = formClient.executeBillQuery("PRD_MO",
                    "FID,FTreeEntity_FEntryId,FTreeEntity_FSeq,FMaterialId.FNumber,"
                            + "FPrdOrgId.FNumber,FWorkShopID.FNumber,FDocumentStatus",
                    "FBillNo='" + order.getWorkOrderNo().replace("'", "''") + "'", 10);
            if (!moResult.isSuccess() || moResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到生产订单：" + order.getWorkOrderNo());
            }
            List<Object> mo = moResult.getRows().get(0);
            String moStatus = mo.size() > 6 ? str(mo.get(6)) : "";
            if (!"C".equals(moStatus)) {
                return WmsOrderSyncResult.fail("生产订单[" + order.getWorkOrderNo()
                        + "]未审核（金蝶状态 " + moStatus + "），审核后才能领料");
            }
            Long moId = toLong(mo.get(0));
            Long moEntryId = toLong(mo.size() > 1 ? mo.get(1) : null);
            Integer moEntrySeq = toInt(mo.size() > 2 ? mo.get(2) : null);
            String parentMaterial = mo.size() > 3 ? str(mo.get(3)) : "";
            String orgNumber = mo.size() > 4 ? str(mo.get(4)) : "";
            String workshop = mo.size() > 5 ? str(mo.get(5)) : "";
            if (moId == null || moEntryId == null) {
                return WmsOrderSyncResult.fail("金蝶生产订单缺少主键/分录内码：" + order.getWorkOrderNo());
            }
            if (oConvertUtils.isEmpty(orgNumber)) {
                return WmsOrderSyncResult.fail("金蝶生产订单未取到生产组织：" + order.getWorkOrderNo());
            }
            if (oConvertUtils.isEmpty(workshop)) {
                return WmsOrderSyncResult.fail("金蝶生产订单未取到车间："
                        + order.getWorkOrderNo() + "（生产领料单车间必录）");
            }
            if (oConvertUtils.isEmpty(parentMaterial)) {
                return WmsOrderSyncResult.fail("金蝶生产订单未取到父项物料：" + order.getWorkOrderNo());
            }

            // 3) 用料清单：必须已审核，且要拿到「子项物料 → 分录内码」映射
            Map<String, Long> ppbomEntryIds;
            String ppbomBillNo;
            WmsKingdeeResult ppbom = formClient.executeBillQuery("PRD_PPBOM", "FBillNo,FDocumentStatus",
                    "FMoBillNo='" + order.getWorkOrderNo().replace("'", "''") + "'", 10);
            if (!ppbom.isSuccess() || ppbom.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到工单[" + order.getWorkOrderNo()
                        + "]的生产用料清单，无法领料");
            }
            ppbomBillNo = str(ppbom.getRows().get(0).get(0));
            String ppbomStatus = str(ppbom.getRows().get(0).get(1));
            if (!"C".equals(ppbomStatus)) {
                return WmsOrderSyncResult.fail("生产用料清单[" + ppbomBillNo + "]未审核（金蝶状态 "
                        + ppbomStatus + "），审核后才能领料");
            }
            WmsKingdeeResult ppbomView = formClient.view("PRD_PPBOM", ppbomBillNo);
            if (!ppbomView.isSuccess()) {
                return WmsOrderSyncResult.fail("读取用料清单[" + ppbomBillNo + "]明细失败："
                        + ppbomView.getMessage());
            }
            ppbomEntryIds = parsePpbomEntries(ppbomView.getRaw());
            if (ppbomEntryIds.isEmpty()) {
                return WmsOrderSyncResult.fail("用料清单[" + ppbomBillNo + "]未解析到子项分录");
            }

            // 4) 仓库 / 仓位
            String stockCode = lookup.stockCode(orgNumber, "stereo");
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code.stereo，组织 " + orgNumber + "）");
            }
            Long stockLocId = lookup.stockLocId(stockCode);

            // 5) 组装分录
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

                // 5.1 用料清单关联：拿不到分录内码说明这条物料不在该工单的用料清单里，领料无从谈起
                Long ppbomEntryId = ppbomEntryIds.get(kingdeeCode);
                if (ppbomEntryId == null) {
                    return WmsOrderSyncResult.fail("物料[" + kingdeeCode + "]不在工单["
                            + order.getWorkOrderNo() + "]的用料清单[" + ppbomBillNo + "]中，无法领料");
                }

                BigDecimal qty = nvl(detail.getActualQty());

                // 5.2 批号：物料启用批号管理时必须存在且为「可用」状态，否则金蝶报「批号已作废」
                String lot = detail.getBatchNo();
                if (oConvertUtils.isNotEmpty(lot)) {
                    String lotError = checkBatchUsable(kingdeeCode, lot, orgNumber);
                    if (lotError != null) {
                        return WmsOrderSyncResult.fail(lotError);
                    }
                }

                // 5.3 库存：领料要扣库存，先在金蝶侧确认够（不够时给出比「更新库存出现异常」更可读的原因）
                String stockError = checkStockAvailable(stockCode, kingdeeCode, lot, qty);
                if (stockError != null) {
                    return WmsOrderSyncResult.fail(stockError);
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("FEntryID", 0);
                // 父项物料：必须给，缺了金蝶只报「分录明细是必填项」
                entry.put("FParentMaterialId", dict(parentMaterial));
                entry.put("FMaterialId", dict(kingdeeCode));
                entry.put("FUnitID", dict(unitNumber));
                entry.put("FBaseUnitId", dict(unitNumber));
                entry.put("FStockUnitId", dict(unitNumber));
                // 数量组五连：单位一致时必须同值，缺 FBaseStockActualQty 会报「基本单位实发数量…要一致」
                entry.put("FAppQty", qty);
                entry.put("FActualQty", qty);
                entry.put("FBaseActualQty", qty);
                entry.put("FStockActualQty", qty);
                entry.put("FBaseStockActualQty", qty);
                entry.put("FStockId", dict(stockCode));
                if (stockLocId != null) {
                    entry.put("FStockLocId", stockLocId);
                }
                entry.put("FStockStatusId", dict(properties.getStockStatusId()));
                entry.put("FOwnerTypeId", properties.getOwnerType());
                entry.put("FOwnerId", dict(orgNumber));
                entry.put("FKeeperTypeId", properties.getKeeperType());
                entry.put("FKeeperId", dict(orgNumber));
                entry.put("FParentOwnerTypeId", properties.getOwnerType());
                entry.put("FParentOwnerId", dict(orgNumber));
                if (oConvertUtils.isNotEmpty(lot)) {
                    entry.put("FLot", dict(lot));
                }
                // 源单/用料清单关联（缺 PPBOM 关联会报「用料清单编号在列表里无法找到」）
                entry.put("FEntryWorkShopId", dict(workshop));
                entry.put("FMoBillNo", order.getWorkOrderNo());
                entry.put("FMoId", moId);
                entry.put("FMoEntryId", moEntryId);
                entry.put("FMoEntrySeq", moEntrySeq);
                entry.put("FPPBomBillNo", ppbomBillNo);
                entry.put("FPPBomEntryId", ppbomEntryId);
                // 序列号子单据体在**分录内**（放头上会报 BillHead 不存在该属性）
                entry.put("FSerialSubEntity", List.of(Map.of("FIsAppInspect", false)));
                entries.add(entry);
            }

            // 单号以金蝶为准：不传 FBillNo，由金蝶按编码规则生成
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FDate", formatDate());
            model.put("FPrdOrgId", dict(orgNumber));
            model.put("FStockOrgId", dict(orgNumber));
            model.put("FWorkShopId", dict(workshop));
            model.put("FBillType", dict(properties.getPickBillType()));
            model.put("FEntity", entries);

            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶生产领料保存失败：" + saved.getMessage());
            }
            String erpBillNo = saved.getNumber();
            if (oConvertUtils.isEmpty(erpBillNo)) {
                return WmsOrderSyncResult.fail("金蝶生产领料保存后未返回单号（WMS单号 " + billNo + "）");
            }
            WmsOrderSyncResult result = submit(erpBillNo);
            if (result.isSuccess()) {
                // 回存失败必须抛出：没有 erp_bill_no，重试会重复开单
                persistErpBillNo(order.getId(), erpBillNo);
            }
            return result;
        } catch (Exception e) {
            log.warn("领料出库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    /**
     * 解析用料清单 View 结果：{@code Result.Result.PPBomEntry[].{Id, MaterialID.Number}}。
     *
     * <p>为什么必须走 View：PPBOM 的分录内码在 {@code ExecuteBillQuery} 里没有可查询字段
     * （FEntryId/FPPBomEntryId 都报「字段不存在」），只有 View 返回的 {@code PPBomEntry[].Id} 能用。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Long> parsePpbomEntries(String raw) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (oConvertUtils.isEmpty(raw)) {
            return result;
        }
        try {
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            com.alibaba.fastjson2.JSONObject res = root.getJSONObject("Result");
            com.alibaba.fastjson2.JSONObject bill = res == null ? null : res.getJSONObject("Result");
            if (bill == null) {
                return result;
            }
            com.alibaba.fastjson2.JSONArray rows = bill.getJSONArray("PPBomEntry");
            if (rows == null) {
                return result;
            }
            for (int i = 0; i < rows.size(); i++) {
                com.alibaba.fastjson2.JSONObject row = rows.getJSONObject(i);
                com.alibaba.fastjson2.JSONObject material = row.getJSONObject("MaterialID");
                String number = material == null ? null : material.getString("Number");
                Long entryId = row.getLong("Id");
                if (oConvertUtils.isNotEmpty(number) && entryId != null) {
                    result.put(number, entryId);
                }
            }
        } catch (Exception e) {
            log.warn("解析用料清单明细失败：{}", e.getMessage());
        }
        return result;
    }

    /**
     * 批号保障：必须存在且可用；缺失时按 {@code batch.auto-create-enabled} 自动创建
     * （2026-10-28 自动化优先：批号主档无 Submit/Audit/Delete 接口，过去只能人工建）。
     *
     * <p>踩过的坑：新建批号若不带 FLotStatus，金蝶默认给 T（不可用），领料时报
     * 「批号已作废或批号状态不正确」——这条错误对用户毫无指向性。
     *
     * @return null 表示通过（含已自动创建）；否则返回给用户看的原因
     */
    private String checkBatchUsable(String materialCode, String lot, String orgNumber) {
        return batchSupport.ensureUsable(materialCode, lot, orgNumber);
    }

    /**
     * 库存校验：领料要扣库存，先确认金蝶侧够发。
     *
     * <p>不查的话，金蝶会在**审核**阶段才报「更新库存出现异常情况」——那时单据已生成，
     * 用户还得手工删单，体验很差。
     *
     * <p><b>取 {@code FBaseQty} 而不是 {@code FQty}</b>（2026-10-27 真机校准）：领料单上报的是
     * 基本单位数量（{@code FBaseActualQty}），而 {@code FQty}（库存单位数量）在物料「库存信息」
     * 未单独维护库存单位时会停留为 0——真机上正是 10 件库存在手、{@code FQty} 却读成 0，
     * 用 FQty 判库存会把正常领料全部误拦。
     *
     * @return null 表示通过；否则返回给用户看的原因
     */
    private String checkStockAvailable(String stockCode, String materialCode, String lot, BigDecimal qty) {
        StringBuilder filter = new StringBuilder("FStockId.FNumber='")
                .append(stockCode.replace("'", "''"))
                .append("' AND FMaterialId.FNumber='").append(materialCode.replace("'", "''")).append("'");
        if (oConvertUtils.isNotEmpty(lot)) {
            filter.append(" AND FLot.FNumber='").append(lot.replace("'", "''")).append("'");
        }
        WmsKingdeeResult inventory = formClient.executeBillQuery("STK_Inventory",
                "FBaseQty", filter.toString(), 200);
        if (!inventory.isSuccess()) {
            log.warn("库存校验查询失败（不拦路）：{}", inventory.getMessage());
            return null;
        }
        BigDecimal available = BigDecimal.ZERO;
        for (List<Object> row : inventory.getRows()) {
            if (!row.isEmpty() && row.get(0) != null) {
                available = available.add(new BigDecimal(str(row.get(0))));
            }
        }
        if (available.compareTo(qty) < 0) {
            return "金蝶仓库[" + stockCode + "]物料[" + materialCode + "]"
                    + (oConvertUtils.isEmpty(lot) ? "" : "批号[" + lot + "]")
                    + "可用库存 " + available.stripTrailingZeros().toPlainString()
                    + " 少于领料数量 " + qty.stripTrailingZeros().toPlainString()
                    + "，请先入库或调整领料数量";
        }
        return null;
    }

    /** 本地已有金蝶单号：按状态跟进——B/C 视为完成，其余补提交。 */
    private WmsOrderSyncResult followUp(WmsOutboundOrder order, String erpBillNo) {
        WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                "FBillNo='" + erpBillNo.replace("'", "''") + "'", 1);
        if (!existing.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶生产领料单查询失败：" + existing.getMessage());
        }
        if (existing.getRows().isEmpty()) {
            return WmsOrderSyncResult.fail("金蝶生产领料单不存在（本地单号 " + erpBillNo
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
            return WmsOrderSyncResult.fail("金蝶生产领料提交失败：" + submitted.getMessage());
        }
        log.info("生产领料回写金蝶成功：ERP单号={}", number);
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

    private Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInt(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? 1 : parsed.intValue();
    }
}
