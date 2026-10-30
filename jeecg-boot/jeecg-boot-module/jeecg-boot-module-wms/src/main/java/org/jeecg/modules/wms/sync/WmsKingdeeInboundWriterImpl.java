package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
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
 * <p>单号以金蝶为准（2026-10-28 改造）：不传 FBillNo（半成品 Push 天然不传，成品 Save 同步对齐），
 * 保存后把金蝶单号回存 {@code wms_production_inbound.erp_bill_no}；
 * 幂等改为「erp_bill_no 有值按它查，无值先用旧 WMS 单号做一次兼容查询（接住改造前存量单）」。
 * <p>PRD_INSTOCK 没有独立不良数量字段，不良数量/原因写入 FNote；纯不良记录不自动回写。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Service
public class WmsKingdeeInboundWriterImpl implements IWmsKingdeeInboundWriter {

    private static final String FORM_ID = "PRD_INSTOCK";
    /** 业务流程为空时在映射表里的哨兵键（空串键会让 Spring 的 Map 绑定抛异常）。 */
    private static final String FLOW_EMPTY_KEY = "__empty__";

    private final WmsKingdeeFormClient formClient;
    private final WmsKingdeeProperties properties;
    private final IWmsKingdeeDefectWriter defectWriter;
    private final WmsProductionInboundMapper inboundMapper;
    private final WmsMaterialCodeResolver materialResolver;
    private final WmsKingdeeBatchSupport batchSupport;
    private final WmsKingdeeLookupSupport lookup;

    public WmsKingdeeInboundWriterImpl(WmsKingdeeFormClient formClient, WmsKingdeeProperties properties,
                                       IWmsKingdeeDefectWriter defectWriter, WmsProductionInboundMapper inboundMapper,
                                       WmsMaterialCodeResolver materialResolver, WmsKingdeeBatchSupport batchSupport,
                                       WmsKingdeeLookupSupport lookup) {
        this.formClient = formClient;
        this.properties = properties;
        this.defectWriter = defectWriter;
        this.inboundMapper = inboundMapper;
        this.materialResolver = materialResolver;
        this.batchSupport = batchSupport;
        this.lookup = lookup;
    }

    @Override
    public WmsOrderSyncResult write(WmsProductionInbound inbound) {
        if (!properties.isEnabled()) {
            return WmsOrderSyncResult.okMock();
        }
        BigDecimal goodQty = inbound.getGoodQty() == null ? BigDecimal.ZERO : inbound.getGoodQty();
        // 方案 B 分支必须最先判：工序汇报来源哪怕良品为 0 也不能去开「其他入库单」，
        // 否则 ERP 侧车间汇报 + WMS 其它入库 = 双份入账（2026-10-30 评审指出顺序风险）
        if (oConvertUtils.isNotEmpty(inbound.getErpEntryKey())) {
            return reconcileReportSourced(inbound);
        }
        if (goodQty.signum() <= 0) {
            // 纯不良品：PRD_INSTOCK 没有不良数量字段，自动开其他入库单记账（spec 2026-10-28）
            if (defectWriter == null) {
                return WmsOrderSyncResult.fail("纯不良品回写器未注入，无法自动开单，请人工处理");
            }
            return defectWriter.write(inbound);
        }
        String billNo = inbound.getInboundNo();
        try {
            // 1) 幂等：本地已回存金蝶单号 → 按它跟进状态
            if (oConvertUtils.isNotEmpty(inbound.getErpBillNo())) {
                return followUp(inbound, inbound.getErpBillNo());
            }
            // 1.1) 兼容查询：改造前按 WMS 单号开过单，接住存量（查到即回存单号），避免重复开单
            WmsKingdeeResult legacy = formClient.executeBillQuery(
                    FORM_ID, "FBillNo,FDocumentStatus", "FBillNo='" + billNo.replace("'", "''") + "'", 1);
            if (!legacy.isSuccess()) {
                return WmsOrderSyncResult.fail("金蝶入库单查询失败：" + legacy.getMessage());
            }
            if (!legacy.getRows().isEmpty()) {
                String status = str(legacy.getRows().get(0).get(1));
                // C 已审核、B 审核中（已提交）都视为已完成；A/Z/D 才需要提交
                if ("C".equals(status) || "B".equals(status)) {
                    persistErpBillNo(inbound.getId(), billNo);
                    return WmsOrderSyncResult.ok();
                }
                WmsOrderSyncResult legacyResult = submit(billNo);
                if (legacyResult.isSuccess()) {
                    persistErpBillNo(inbound.getId(), billNo);
                }
                return legacyResult;
            }

            // 2) 生产订单来源分录（主产品行）：FMoId / FMoEntryId / FMoEntrySeq / 车间 / 生产组织
            WmsKingdeeResult moResult = formClient.executeBillQuery("PRD_MO",
                    "FID,FBillNo,FWorkShopID,FWorkShopID.FNumber,FQty,FProductType,"
                            + "FTreeEntity_FEntryId,FTreeEntity_FSeq,FPrdOrgId,FPrdOrgId.FNumber,"
                            // 业务流程：半成品下推时金蝶只允许「空值/生产直接入库流程」
                            + "FBFLowId.FName",
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
            String businessFlow = mo.size() > 10 ? str(mo.get(10)) : "";

            // 3) 物料 / 单位
            String kingdeeMaterialCode = materialResolver.resolveKingdeeCode(inbound.getMaterialCode());
            // 批号保障：金蝶批号主档缺失时按配置自动创建为可用（批号主档无审核接口，只能 Save）
            String lotError = batchSupport.ensureUsable(kingdeeMaterialCode, inbound.getBatchNo(), moOrgNumber);
            if (lotError != null) {
                return WmsOrderSyncResult.fail(lotError);
            }
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
                // 优先用 **物理货位上的金蝶仓位属性**（2026-10-28 模型：金蝶仓位是挂在货位上的分类属性），
                // 货位没挂属性时才回退到 stock-loc 配置
                stockLocId = lookup.stockLocIdByLocation(inbound.getLocationId());
                // 0/≤0 一律视为「没拿到」：内码为正整数，防御脏值（单测里 Mockito 对 Long 默认返回 0）
                if (stockLocId == null || stockLocId <= 0) {
                    stockLocId = resolveStockLocId(stockCode, flexId);
                }
                if (stockLocId == null || stockLocId <= 0) {
                    return WmsOrderSyncResult.fail("仓库[" + stockCode + "]启用仓位管理："
                            + "请给货位挂金蝶仓位属性，或配置 stock-loc[" + stockCode + "]=仓位编号");
                }
            }

            // 半成品：物料启用 BOM 版本管理时 FBomId 在 Save 时被忽略，必须走「生产订单下推」
            if (properties.isPushInboundEnabled()
                    && WmsConstants.PRODUCT_TYPE_SEMI.equals(inbound.getProductType())) {
                return writeByPush(inbound, goodQty, businessFlow, stockCode, stockLocId);
            }

            // 4) 组装 Model（单号以金蝶为准：不传 FBillNo，由金蝶按编码规则生成）
            Map<String, Object> model = new LinkedHashMap<>();
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
                return WmsOrderSyncResult.fail("金蝶生产入库保存失败：" + savedMessage);
            }
            String erpBillNo = saved.getNumber();
            if (oConvertUtils.isEmpty(erpBillNo)) {
                return WmsOrderSyncResult.fail("金蝶生产入库保存后未返回单号（WMS单号 " + billNo + "）");
            }
            WmsOrderSyncResult result = submit(erpBillNo);
            if (result.isSuccess()) {
                // 回存失败必须抛出：没有 erp_bill_no，重试会重复开单
                persistErpBillNo(inbound.getId(), erpBillNo);
            }
            return result;
        } catch (Exception e) {
            log.warn("生产入库[{}]回写金蝶异常：{}", billNo, e.getMessage());
            return WmsOrderSyncResult.fail("回写金蝶异常：" + e.getMessage());
        }
    }

    /**
     * 工序汇报来源的入库单：只对账，不开单。
     *
     * <p>ERP 侧的生产入库单是车间在金蝶「工序汇报」时生成的（{@code FSrcBillNo=GXHB…}），
     * WMS 推进只负责本仓记账。这里按 GXHB 单号回查 ERP 是否已入库：
     * 命中则把真实 SCRK 单号回存到 {@code erp_bill_no}，未命中说明车间还没汇报。
     */
    private WmsOrderSyncResult reconcileReportSourced(WmsProductionInbound inbound) {
        // 汇报单号从 erp_entry_key 的 # 前段取：erp_bill_no 回填成 ERP 单号后仍在用，
        // 再拿它去查 FSrcBillNo 会永久未命中，文案还会把 SCRK 说成 GXHB
        String gxhbNo = reportBillNoOf(inbound);
        if (oConvertUtils.isEmpty(gxhbNo)) {
            return WmsOrderSyncResult.fail("工序汇报来源缺少 GXHB 单号，无法对账（入库单 "
                    + inbound.getInboundNo() + "）");
        }
        try {
            WmsKingdeeResult result = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus,FRealQty",
                    "FSrcBillNo='" + gxhbNo.replace("'", "''") + "'", 20);
            if (!result.isSuccess()) {
                return WmsOrderSyncResult.fail("ERP 生产入库单对账查询失败：" + result.getMessage());
            }
            // 一张工序汇报可能按分录/批次生成多张入库单：优先取数量与本单据一致的，
            // 都不同才退回第一张——盲取第一张会让按单号的追溯全部错位。
            String fallback = null;
            for (List<Object> row : result.getRows()) {
                String billNo = str(row.get(0));
                String status = str(row.size() > 1 ? row.get(1) : null);
                if (oConvertUtils.isEmpty(billNo) || !("B".equals(status) || "C".equals(status))) {
                    continue;
                }
                BigDecimal billQty = toDecimal(row.size() > 2 ? row.get(2) : null);
                BigDecimal mine = inbound.getGoodQty() == null ? BigDecimal.ZERO : inbound.getGoodQty();
                if (mine.signum() > 0 && billQty.compareTo(mine) == 0) {
                    persistErpBillNo(inbound.getId(), billNo);
                    log.info("工序汇报入库对账成功（数量匹配）：WMS单号={}，GXHB={}，ERP单号={}",
                            inbound.getInboundNo(), gxhbNo, billNo);
                    return WmsOrderSyncResult.ok();
                }
                if (fallback == null) {
                    fallback = billNo;
                }
            }
            if (fallback != null) {
                persistErpBillNo(inbound.getId(), fallback);
                log.warn("工序汇报入库对账：GXHB={} 有多张入库单且数量都不匹配，回填第一张 {}，请人工核对",
                        gxhbNo, fallback);
                return WmsOrderSyncResult.ok();
            }
            return WmsOrderSyncResult.fail("ERP 尚未汇报入库（等待车间在金蝶完成工序汇报），GXHB 单号："
                    + gxhbNo);
        } catch (Exception e) {
            log.warn("工序汇报入库[{}]对账异常：{}", inbound.getInboundNo(), e.getMessage());
            return WmsOrderSyncResult.fail("对账异常：" + e.getMessage());
        }
    }

    /**
     * 半成品走「下推」：PRD_MO --Push--> PRD_INSTOCK 草稿 --Save--> Submit。
     *
     * <p>为什么不能直接 Save：物料启用 BOM 版本管理时 {@code FBomId} 在 Save 时被服务端忽略
     * （元数据 IsEditVisible=false），只能由转换规则从生产订单带出（2026-10-27 实测）。
     *
     * <p>业务流程不在映射表里时**直接报金蝶原文**，不自动改工单流程、不静默重试。
     */
    private WmsOrderSyncResult writeByPush(WmsProductionInbound inbound, BigDecimal goodQty,
                                           String businessFlow, String stockCode, Long stockLocId) {
        String flowKey = oConvertUtils.isEmpty(businessFlow) ? FLOW_EMPTY_KEY : businessFlow.trim();
        String targetBillType = null;
        List<String> allowedFlows = new ArrayList<>();
        for (WmsKingdeeProperties.FlowBillType mapping : properties.getPushFlowBillTypes()) {
            if (mapping == null || oConvertUtils.isEmpty(mapping.getFlow())) {
                continue;
            }
            allowedFlows.add(mapping.getFlow());
            if (mapping.getFlow().trim().equals(flowKey)) {
                targetBillType = mapping.getBillType();
            }
        }
        if (oConvertUtils.isEmpty(targetBillType)) {
            return WmsOrderSyncResult.fail("生产订单[" + inbound.getWorkOrderNo() + "]的业务流程为「"
                    + (oConvertUtils.isEmpty(businessFlow) ? "空" : businessFlow) + "」，不在允许下推的范围"
                    + allowedFlows + "。"
                    + "金蝶原文：业务流程字段的值，必须是如下值之一：空值, " + properties.getDefaultBusinessFlow()
                    + "。请调整生产订单的业务流程，或补充 push-flow-bill-type-map 配置");
        }

        Map<String, Object> pushData = new LinkedHashMap<>();
        // 下推接口的 Numbers 必须是字符串：传数组会报「编码值[...]不存在」
        pushData.put("Numbers", inbound.getWorkOrderNo());
        pushData.put("TargetFormId", FORM_ID);
        pushData.put("TargetBillTypeId", targetBillType);
        pushData.put("IsEnableDefaultRule", true);
        WmsKingdeeResult pushed = formClient.push("PRD_MO", pushData);
        if (!pushed.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶下推失败：" + pushed.getMessage());
        }
        String targetId = pushed.getId();

        WmsKingdeeResult target = formClient.executeBillQuery(FORM_ID,
                "FBillNo,FEntity_FEntryId", "FID=" + targetId, 1);
        if (!target.isSuccess() || target.getRows().isEmpty()) {
            return WmsOrderSyncResult.fail("金蝶下推后未查到目标单（FID=" + targetId + "）");
        }
        List<Object> targetRow = target.getRows().get(0);
        Object targetEntryId = targetRow.size() > 1 ? targetRow.get(1) : null;
        if (targetEntryId == null) {
            return WmsOrderSyncResult.fail("金蝶下推目标单缺少分录内码（FID=" + targetId + "）");
        }

        // 更新草稿：数量/仓库/批号；IsDeleteEntry=false 才不会删掉源单带出的分录
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FID", targetId);
        model.put("FBillType", dict(targetBillType));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FEntryID", targetEntryId);
        entry.put("FStockId", dict(stockCode));
        if (stockLocId != null) {
            entry.put("FStockLocId", stockLocId);
        }
        if (oConvertUtils.isNotEmpty(inbound.getBatchNo())) {
            entry.put("FLot", dict(inbound.getBatchNo()));
        }
        entry.put("FRealQty", goodQty);
        entry.put("FNote", note(inbound));
        model.put("FEntity", List.of(entry));

        WmsKingdeeResult saved = formClient.save(FORM_ID, model, false);
        if (!saved.isSuccess()) {
            String message = saved.getMessage() == null ? "" : saved.getMessage();
            return WmsOrderSyncResult.fail("金蝶生产入库保存失败（下推草稿）：" + message);
        }
        String number = oConvertUtils.isNotEmpty(saved.getNumber())
                ? saved.getNumber() : str(targetRow.get(0));
        if (oConvertUtils.isEmpty(number)) {
            return WmsOrderSyncResult.fail("金蝶下推草稿保存后未取到单据编号（FID=" + targetId + "）");
        }
        WmsOrderSyncResult result = submit(number);
        if (result.isSuccess()) {
            // 回存失败必须抛出：没有 erp_bill_no，重试会重复开单
            persistErpBillNo(inbound.getId(), number);
        }
        return result;
    }

    /** 本地已有金蝶单号：按状态跟进——B/C 视为完成，其余补提交。 */
    private WmsOrderSyncResult followUp(WmsProductionInbound inbound, String erpBillNo) {
        WmsKingdeeResult existing = formClient.executeBillQuery(FORM_ID, "FBillNo,FDocumentStatus",
                "FBillNo='" + erpBillNo.replace("'", "''") + "'", 1);
        if (!existing.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶入库单查询失败：" + existing.getMessage());
        }
        if (existing.getRows().isEmpty()) {
            return WmsOrderSyncResult.fail("金蝶入库单不存在（本地单号 " + erpBillNo
                    + "，可能被人工删除），请核对后清理 erp_bill_no");
        }
        String status = str(existing.getRows().get(0).get(1));
        if ("C".equals(status) || "B".equals(status)) {
            return WmsOrderSyncResult.ok();
        }
        return submit(erpBillNo);
    }

    /** 回存金蝶单号（只更新 erp_bill_no 列）。失败抛出 → 本次同步失败，防重试重复开单。 */
    private void persistErpBillNo(String inboundId, String erpBillNo) {
        WmsProductionInbound patch = new WmsProductionInbound();
        patch.setId(inboundId);
        patch.setErpBillNo(erpBillNo);
        inboundMapper.updateById(patch);
    }

    private WmsOrderSyncResult submit(String number) {
        WmsKingdeeResult submitted = formClient.submit(FORM_ID, List.of(number));
        if (!submitted.isSuccess()) {
            return WmsOrderSyncResult.fail("金蝶生产入库提交失败：" + submitted.getMessage());
        }
        log.info("生产入库回写金蝶成功：ERP单号={}", number);
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

    /** 从 erp_entry_key（形如 "GXHB659871#0#10"）取汇报单号；没有则退回 erp_bill_no。 */
    private String reportBillNoOf(WmsProductionInbound inbound) {
        String entryKey = inbound.getErpEntryKey();
        if (oConvertUtils.isNotEmpty(entryKey) && entryKey.contains("#")) {
            String head = entryKey.substring(0, entryKey.indexOf('#')).trim();
            if (oConvertUtils.isNotEmpty(head)) {
                return head;
            }
        }
        return inbound.getErpBillNo();
    }

    /** 文本转数量：金蝶回传可能是数字或字符串，解析失败按 0（数量匹配只是择优，不是硬校验）。 */
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
}
