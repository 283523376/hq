package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 生产入库 → 金蝶 PRD_INSTOCK。
 *
 * <p>幂等键：FBillNo = WMS inboundNo；写入前先查，已审核直接成功，草稿/审核中提交。
 * <p>PRD_INSTOCK 没有独立不良数量字段，不良数量/原因写入 FNote；纯不良记录不自动回写。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Service
public class WmsKingdeeInboundWriterImpl implements IWmsKingdeeInboundWriter {

    private static final String FORM_ID = "PRD_INSTOCK";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeInboundWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.properties = properties;
    }

    @Override
    public WmsOrderSyncResult write(WmsProductionInbound inbound) {
        if (!properties.isEnabled()) {
            return WmsOrderSyncResult.okMock();
        }
        BigDecimal goodQty = inbound.getGoodQty() == null ? BigDecimal.ZERO : inbound.getGoodQty();
        if (goodQty.signum() <= 0) {
            return WmsOrderSyncResult.fail("金蝶生产入库单不支持纯不良品回写（PRD_INSTOCK 无不良数量字段），请人工处理");
        }
        String billNo = inbound.getInboundNo();
        try {
            // 1) 幂等：已存在则按状态处理
            WmsKingdeeResult existing = formClient.executeBillQuery(
                    FORM_ID, "FBillNo,FDocumentStatus", "FBillNo='" + billNo.replace("'", "''") + "'", 1);
            if (!existing.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶入库单查询失败：" + existing.getMessage());
            }
            if (!existing.getRows().isEmpty()) {
                String status = str(existing.getRows().get(0).get(1));
                // C 已审核、B 审核中（已提交）都视为已完成；A/Z/D 才需要提交
                if ("C".equals(status) || "B".equals(status)) {
                    return WmsOrderSyncResult.ok();
                }
                return submit(billNo);
            }

            // 2) 生产订单来源分录（主产品行）：FMoId / FMoEntryId / FMoEntrySeq / 车间 / 生产组织
            WmsKingdeeResult moResult = formClient.executeBillQuery("PRD_MO",
                    "FID,FBillNo,FWorkShopID,FWorkShopID.FNumber,FQty,FProductType,"
                            + "FTreeEntity_FEntryId,FTreeEntity_FSeq,FPrdOrgId,FPrdOrgId.FNumber",
                    "FBillNo='" + inbound.getWorkOrderNo().replace("'", "''") + "'", 10);
            if (!moResult.isSuccess() || moResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到生产订单：" + inbound.getWorkOrderNo());
            }
            List<Object> mo = null;
            for (List<Object> row : moResult.getRows()) {
                if (row.size() > 5 && "1".equals(str(row.get(5)))) {
                    mo = row;
                    break;
                }
            }
            if (mo == null) {
                mo = moResult.getRows().get(0);
            }
            Long moBillId = toLong(mo.get(0));
            String workshopNumber = mo.size() > 3 ? str(mo.get(3)) : "";
            Long moEntryId = toLong(mo.size() > 6 ? mo.get(6) : null);
            Integer moEntrySeq = toInt(mo.size() > 7 ? mo.get(7) : null, 1);
            String moBillNo = str(mo.get(1));
            if (moBillId == null || moEntryId == null) {
                return WmsOrderSyncResult.fail("金蝶生产订单缺少主键/分录内码：" + moBillNo);
            }
            // 入库单组织必须跟随生产订单的生产组织（订单可能属于 100/101 任一组织），
            // 不能固定用配置的默认组织；查不到时才回退默认组织并告警
            Long moOrgId = mo.size() > 8 ? toLong(mo.get(8)) : null;
            String moOrgNumber = mo.size() > 9 ? str(mo.get(9)) : "";
            if (oConvertUtils.isEmpty(moOrgNumber)) {
                moOrgNumber = properties.getDefaultOrgNumber();
                log.warn("金蝶生产订单[{}]未取到生产组织编号，回退默认组织 {}", moBillNo, moOrgNumber);
            }
            if (moOrgId == null) {
                moOrgId = toLong(properties.getDefaultOrgId());
            }

            // 3) 物料 / 单位
            String kingdeeMaterialCode = properties.getMaterialMap()
                    .getOrDefault(inbound.getMaterialCode(), inbound.getMaterialCode());
            WmsKingdeeResult materialResult = formClient.executeBillQuery("BD_MATERIAL",
                    "FMATERIALID,FNumber,FName,FBaseUnitId,FCreateOrgId,FUseOrgId",
                    "FNumber='" + kingdeeMaterialCode.replace("'", "''") + "'", 10);
            if (!materialResult.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶物料查询失败：" + materialResult.getMessage());
            }
            if (materialResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到物料编码：" + kingdeeMaterialCode);
            }
            // 同一物料编码可能存在于多个组织：优先取 FUseOrgId = 生产订单组织 的记录
            List<Object> material = null;
            for (List<Object> row : materialResult.getRows()) {
                if (row.size() > 5 && String.valueOf(row.get(5)).equals(String.valueOf(moOrgId))) {
                    material = row;
                    break;
                }
            }
            if (material == null) {
                material = materialResult.getRows().get(0);
                log.warn("金蝶物料[{}]未找到组织[{}]的记录，退回第一条（id={}）",
                        kingdeeMaterialCode, moOrgId, material.get(0));
            }
            Object materialId = material.get(0);
            Object unitId = material.size() > 3 ? material.get(3) : null;
            if (materialId == null || unitId == null) {
                return WmsOrderSyncResult.fail("金蝶物料缺少内部ID或基本单位：" + kingdeeMaterialCode);
            }
            // 单位编码（Save 的基础资料字段要求 {FNumber:...} 字典）
            WmsKingdeeResult unitResult = formClient.executeBillQuery("BD_UNIT",
                    "FUnitId,FNumber", "FUnitId=" + unitId, 1);
            if (!unitResult.isSuccess() || unitResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到单位编码（FUnitId=" + unitId + "）");
            }
            String unitNumber = str(unitResult.getRows().get(0).get(1));

            // 4) 仓库：仓库是组织级基础资料，优先按生产订单组织取覆盖配置
            String stockKey = WmsConstants.PRODUCT_TYPE_SEMI.equals(inbound.getProductType())
                    ? "stereo" : WmsConstants.PRODUCT_TYPE_FINISHED.equals(inbound.getProductType()) ? "agile" : null;
            if (stockKey == null) {
                return WmsOrderSyncResult.fail("未知产品类型：" + inbound.getProductType());
            }
            Map<String, String> orgStock = properties.getStockCodeByOrg().get(moOrgNumber);
            String stockCode = orgStock == null ? null : orgStock.get(stockKey);
            if (oConvertUtils.isEmpty(stockCode)) {
                stockCode = properties.getStockCode().get(stockKey);
            }
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsOrderSyncResult.fail("未配置金蝶仓库编码（stock-code." + stockKey
                        + "，组织 " + moOrgNumber + "）");
            }
            WmsKingdeeResult stockResult = formClient.executeBillQuery("BD_STOCK",
                    "FStockId,FNumber,FFlexId", "FNumber='" + stockCode.replace("'", "''") + "'", 1);
            if (!stockResult.isSuccess() || stockResult.getRows().isEmpty()) {
                return WmsOrderSyncResult.fail("金蝶未找到仓库编码：" + stockCode);
            }
            List<Object> stockRow = stockResult.getRows().get(0);
            Object stockId = stockRow.get(0);
            // 启用仓位管理的仓库（FFlexId>0）必须带 FStockLocId，否则 Save 报「仓位不能为空」
            Long flexId = stockRow.size() > 2 ? toLong(stockRow.get(2)) : null;
            Long stockLocId = null;
            if (flexId != null && flexId > 0) {
                stockLocId = resolveStockLocId(stockCode, flexId);
                if (stockLocId == null) {
                    return WmsOrderSyncResult.fail("仓库[" + stockCode + "]启用仓位管理："
                            + "请配置 stock-loc[" + stockCode + "]=仓位编号");
                }
            }

            // 4) 组装 Model
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("FBillNo", billNo);
            model.put("FDate", formatDate(inbound.getCompleteTime() != null
                    ? inbound.getCompleteTime() : inbound.getSubmitTime()));
            model.put("FPrdOrgId", dict(moOrgNumber));
            model.put("FStockOrgId", dict(moOrgNumber));
            model.put("FBillType", dict(properties.getBillType()));
            model.put("FOwnerId0", dict(moOrgNumber));
            model.put("FOwnerTypeId", properties.getOwnerType());
            model.put("FOwnerId", dict(moOrgNumber));

            Map<String, Object> entry = new LinkedHashMap<>();
            // 来源字段必须在业务字段之前（金蝶保存顺序要求）
            entry.put("FIsNew", false);
            entry.put("FMoBillNo", moBillNo);
            entry.put("FMoId", moBillId);
            entry.put("FMoEntryId", moEntryId);
            entry.put("FMoEntrySeq", moEntrySeq);
            // 主产品行必须显式给「生产订单主产品分录」，只给 FMoEntryId 会报
            // 「生产订单主产品分录不能为空」
            entry.put("FMOMAINENTRYID", moEntryId);
            entry.put("FSrcBillType", "PRD_MO");
            entry.put("FSrcInterId", moBillId);
            entry.put("FSrcBillNo", moBillNo);
            entry.put("FSrcEntryId", moEntryId);
            entry.put("FSrcEntrySeq", moEntrySeq);
            entry.put("FEntity_Link", List.of(Map.of(
                    "FEntity_Link_FRuleId", "PRD_MO2INSTOCK",
                    "FEntity_Link_FSTableName", "T_PRD_MOENTRY",
                    "FEntity_Link_FSBillId", moBillId,
                    "FEntity_Link_FSId", moEntryId)));
            entry.put("FWorkShopID", dict(workshopNumber));
            entry.put("FMaterialId", dict(kingdeeMaterialCode));
            entry.put("FProductType", "1");
            entry.put("FInStockType", properties.getInStockType());
            entry.put("FUnitID", dict(unitNumber));
            entry.put("FBaseUnitId", dict(unitNumber));
            entry.put("FOwnerTypeId", properties.getOwnerType());
            entry.put("FOwnerId", dict(moOrgNumber));
            entry.put("FStockId", dict(stockCode));
            // 仓位：标量传分录内码（FStockLocId 嵌套字典格式金蝶不解析，实测标量可用）
            if (stockLocId != null) {
                entry.put("FStockLocId", stockLocId);
            }
            // 批号：物料启用批号时必填；WMS 批次号需在 BD_BatchMainFile 中存在
            if (oConvertUtils.isNotEmpty(inbound.getBatchNo())) {
                entry.put("FLot", dict(inbound.getBatchNo()));
            }
            entry.put("FStockStatusId", dict(properties.getStockStatusId()));
            entry.put("FKeeperTypeId", properties.getKeeperType());
            entry.put("FKeeperId", dict(moOrgNumber));
            entry.put("FRealQty", goodQty);
            entry.put("FNote", note(inbound));
            model.put("FEntity", List.of(entry));

            // 5) Save + Submit
            WmsKingdeeResult saved = formClient.save(FORM_ID, model);
            if (!saved.isSuccess()) {
                String savedMessage = saved.getMessage() == null ? "" : saved.getMessage();
                // 半成品（物料启用 BOM 版本管理）目前无法回写：金蝶 PRD_INSTOCK 的
                // FBomId 在 Save 时被服务端忽略（实测传任何值都报「BOM版本不能为空」，
                // 字段元数据 IsEditVisible=false），必须由生产订单下推带出。
                // 用固定前缀标识出来，前端据此禁用重试按钮。
                if (savedMessage.contains("BOM版本")) {
                    return WmsOrderSyncResult.fail(WmsConstants.SYNC_BLOCK_BOM_PENDING
                            + "半成品回写需从生产订单下推带出 BOM 版本，正在与金蝶确认方案，暂不支持自动回写；"
                            + "金蝶原文：" + savedMessage);
                }
                return WmsOrderSyncResult.fail("金蝶生产入库保存失败：" + savedMessage);
            }
            String number = oConvertUtils.isEmpty(saved.getNumber()) ? billNo : saved.getNumber();
            return submit(number);
        } catch (Exception e) {
            log.warn("生产入库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶生产入库提交失败：" + submitted.getMessage());
        }
        log.info("生产入库回写金蝶成功：FBillNo={}", number);
        return WmsOrderSyncResult.ok();
    }

    /**
     * 解析仓库的仓位分录内码：stock-loc 配置仓位编号（FFlexValueNumber，如 01），
     * 经 BD_FLEXVALUES 值集（FFlexId→值集编号）View 出 FLEXVALUESENTRY 匹配分录 Id。
     * 找不到配置或分录时返回 null。
     */
    private Long resolveStockLocId(String stockCode, long flexId) {
        String locNumber = properties.getStockLoc().get(stockCode);
        if (oConvertUtils.isEmpty(locNumber)) {
            return null;
        }
        try {
            WmsKingdeeResult setResult = formClient.executeBillQuery("BD_FLEXVALUES",
                    "FID,FNUMBER", "FID=" + flexId, 1);
            if (!setResult.isSuccess() || setResult.getRows().isEmpty()) {
                log.warn("金蝶仓位值集未找到：FFlexId={}", flexId);
                return null;
            }
            String setNumber = str(setResult.getRows().get(0).get(1));
            WmsKingdeeResult viewResult = formClient.view("BD_FLEXVALUES", setNumber);
            if (!viewResult.isSuccess()) {
                log.warn("金蝶仓位值集[{}]查看失败：{}", setNumber, viewResult.getMessage());
                return null;
            }
            Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(viewResult.getRaw(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = root.get("Result") instanceof Map
                    ? (Map<String, Object>) root.get("Result") : null;
            Object model = result == null ? null : result.get("Result");
            Object entries = model instanceof Map ? ((Map<?, ?>) model).get("FLEXVALUESENTRY") : null;
            if (!(entries instanceof List)) {
                return null;
            }
            for (Object item : (List<?>) entries) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) item;
                if (locNumber.equals(str(row.get("Number")))) {
                    return toLong(row.get("Id"));
                }
            }
            log.warn("金蝶仓位值集[{}]中没有仓位编号[{}]", setNumber, locNumber);
            return null;
        } catch (Exception e) {
            log.warn("解析仓库[{}]仓位失败：{}", stockCode, e.getMessage());
            return null;
        }
    }

    private String note(WmsProductionInbound inbound) {
        StringBuilder sb = new StringBuilder();
        sb.append("WMS入库单:").append(inbound.getInboundNo());
        if (inbound.getBadQty() != null && inbound.getBadQty().signum() > 0) {
            sb.append("；不良数量:").append(inbound.getBadQty());
        }
        if (oConvertUtils.isNotEmpty(inbound.getDefectRemark())) {
            sb.append("；不良原因:").append(inbound.getDefectRemark());
        }
        if (oConvertUtils.isNotEmpty(inbound.getBatchNo())) {
            sb.append("；批次:").append(inbound.getBatchNo());
        }
        if (oConvertUtils.isNotEmpty(inbound.getCarrierCode())) {
            sb.append("；载具:").append(inbound.getCarrierCode());
        }
        String text = sb.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** 基础资料字典：{FNumber: "编码"}。 */
    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    /** 基础资料字典：{Id: 内码}；纯数字字符串转 Long，避免金蝶类型校验失败。 */
    private Map<String, Object> idDict(Object id) {
        Object value = id;
        if (id instanceof String && ((String) id).matches("\\d+")) {
            value = Long.valueOf((String) id);
        }
        return Map.of("Id", value);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInt(Object value, int defaultValue) {
        Long parsed = toLong(value);
        return parsed == null ? defaultValue : parsed.intValue();
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
