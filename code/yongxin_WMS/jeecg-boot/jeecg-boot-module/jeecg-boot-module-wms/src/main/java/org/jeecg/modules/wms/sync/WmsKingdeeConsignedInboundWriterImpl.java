package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 受托加工材料入库单回写（V2.4 §3.2 受托入库）。
 *
 * <p><b>2026-11-04 真账套实测结论：不能直接 Save，必须 Push → Save → Submit</b>
 * （测试账套 6a9aaaadaf7ee6，已实际生成单据 STCLRK001437）。
 * 直接 Save 到 {@code STK_OEMInStock} 必然失败，因为转换规则带出的字段（源单关联、货主、
 * 保管者等）无法由报文补齐；这些字段只能由「销售订单 → 受托材料入库」转换规则从源单带出。
 *
 * <p><b>第一步 Push：从受托加工销售订单下推</b>
 * <ul>
 *   <li>顶层 {@code FormId} 必须是<b>源单</b> {@value #SOURCE_FORM_ID}，传目标单会报
 *       「转换规则的源单与 FormId 值不一致」；</li>
 *   <li>{@code RuleId} = {@value #PUSH_RULE_ID}（「销售订单受托材料入库」）；</li>
 *   <li>{@code Ids} 传源单内码（字符串）；{@code Numbers} 必须是空集合——传编号一律报
 *       「编码值不存在」；{@code CustomParams} 必须是字典（传 {@code []} 报「不能转换为字典类型」）。</li>
 * </ul>
 *
 * <p><b>第二步 Save：把明细写进刚下推的草稿</b>
 * <ul>
 *   <li>分录实体键 {@code FBillEntry}（不是 {@code FEntity}，也不是 {@code FOEMInStockEntry}）；</li>
 *   <li>物料 {@code FMaterialId}（样例里的 {@code FMatId} 是陷阱，会报「物料编码必填」）；</li>
 *   <li>数量 {@code FQty} 且<b>必须同时给</b> {@code FBaseQty}，否则报「实收数量（基本单位）必须大于 0」；</li>
 *   <li>仓库 {@code FStockID}、库存状态 {@code FStockStatusID}——<b>ID 必须大写</b>，
 *       小写 {@code FStockId} 会被当成没传；</li>
 *   <li>单位 {@code FUnitId} 取物料主数据单位（金蝶 BD_UNIT 编码如 {@code zhi}/{@code Pcs}，不是中文名）；</li>
 *   <li>货主 {@code FOwnerTypeId=BD_Customer} + {@code FOwnerId}（受托方客户编码）；
 *       保管者 {@code FKeeperTypeId=BD_KeeperOrg} + {@code FKeeperId}（组织编码，如 101）。</li>
 * </ul>
 *
 * <p><b>第三步 Submit</b>：用 Save 返回的单号提交（响应里是 {@code STCLRK00xxxx}）。
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo}</b>：单号以 ERP 为准（2026-10-28 决策）；Save 层必须带
 *       {@code FID} 定位 Push 出的草稿，且 {@code IsDeleteEntry=true} 用 WMS 明细覆盖源单分录；</li>
 *   <li>只传业务字段：日期、受托方（客户）、物料编码、实收数量、批次、仓库、备注；</li>
 *   <li>数量取「实收数量」，为空回退「应收数量」——上架前调用时只有应收是常态；
 *       <b>短装行（数量 0）跳过</b>，负数仍报错；只有全部行都不可回写时才整单失败；</li>
 *   <li>日期取审核时间（{@code auditTime}），为空回退创建时间；</li>
 *   <li>明细为空显式失败，不发空报文（金蝶只会报「分录不能为空」，本地报错更好排查）；</li>
 *   <li>任何一步失败都把金蝶原文透传；已 Push 出的草稿只记日志供人工清理，<b>不自动删单</b>。</li>
 * </ul>
 *
 * <p>{@link #isEnabled()} 暴露 {@code wms.sync.erp.kingdee.enabled} 供调用方发起前判断；
 * Writer 不因开关关闭而返回 mock 成功。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsKingdeeConsignedInboundWriterImpl implements IWmsKingdeeConsignedInboundWriter {

    private static final String BILL_NAME = "受托加工材料入库单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_CONSIGNED_INBOUND;
    /** 备注长度上限：金蝶 FNote 超长会整单保存失败。 */
    private static final int NOTE_MAX_LENGTH = 500;

    /** 下推源单：受托加工销售订单（Push 顶层 FormId 必须是源单）。 */
    static final String SOURCE_FORM_ID = "SAL_SaleOrder";
    /** 转换规则「销售订单受托材料入库」（SAL_SaleOrder → STK_OEMInStock）。 */
    static final String PUSH_RULE_ID = "Sal_Order-STK_OEMInStock";
    /** 分录实体键：真账套实测为 FBillEntry（不是 FEntity / FOEMInStockEntry）。 */
    static final String ENTRY_KEY = "FBillEntry";
    /** 受托材料货权属于客户，货主类型固定为客户。 */
    private static final String OWNER_TYPE_CUSTOMER = "BD_Customer";
    /** 保管者是受托方组织。 */
    private static final String KEEPER_TYPE_ORG = "BD_KeeperOrg";
    /** 库存状态「可用」（BD_StockStatus.FNumber，2026-11-04 实测）。 */
    private static final String STOCK_STATUS_ID = "KCZT01_SYS";

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsInboundDetailMapper detailMapper;
    private final WmsKingdeeProperties properties;
    private final WmsWarehouseCodeResolver warehouseResolver;
    private final IWmsMaterialService materialService;

    public WmsKingdeeConsignedInboundWriterImpl(WmsKingdeeFormClient formClient,
                                                IWmsSystemConfigService configService,
                                                WmsInboundDetailMapper detailMapper,
                                                WmsKingdeeProperties properties,
                                                WmsWarehouseCodeResolver warehouseResolver,
                                                IWmsMaterialService materialService) {
        this.formClient = formClient;
        this.configService = configService;
        this.detailMapper = detailMapper;
        this.properties = properties;
        this.warehouseResolver = warehouseResolver;
        this.materialService = materialService;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public WmsKingdeeResult write(WmsInboundOrder order) {
        if (order == null || oConvertUtils.isEmpty(order.getId())) {
            throw new IllegalArgumentException(BILL_NAME + "回写缺少 WMS 入库单（id 为空）");
        }
        if (!WmsConstants.INBOUND_SOURCE_CONSIGNED.equals(order.getSourceType())) {
            throw new IllegalArgumentException("入库单[" + order.getOrderNo() + "]来源类型为["
                    + order.getSourceType() + "]，不能回写" + BILL_NAME
                    + "（要求 source_type=CONSIGNED）");
        }
        // formId 必须最先校验：未配置时连明细都不该查
        String formId = requireFormId();

        List<WmsInboundDetail> details = detailMapper.selectList(
                new QueryWrapper<WmsInboundDetail>().eq("inbound_id", order.getId()));
        if (details == null || details.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]没有明细，无法回写金蝶"
                    + BILL_NAME);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (WmsInboundDetail detail : details) {
            // 空物料行最先跳过：没有物料编码的行走不到金蝶，数量是否合法对它没有意义
            if (oConvertUtils.isEmpty(detail.getMaterialCode())) {
                log.warn("入库单[{}]存在物料编码为空的明细行，跳过回写（明细 id={}）",
                        order.getOrderNo(), detail.getId());
                continue;
            }
            BigDecimal qty = qtyOf(detail);
            if (qty == null) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]数量为空，无法回写金蝶" + BILL_NAME);
            }
            if (qty.signum() < 0) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]数量为负（" + qty + "），无法回写金蝶" + BILL_NAME);
            }
            if (qty.signum() == 0) {
                // 短装行：库存侧已按跳过处理，ERP 也不应出现 0 数量分录
                log.info("入库单[{}]物料[{}]数量为 0（短装），跳过该行回写",
                        order.getOrderNo(), detail.getMaterialCode());
                continue;
            }
            // 仓库逐行解析（明细 location_id 上架时写入）；解析不到显式失败，不省略必填字段
            String stockCode = warehouseResolver.byLocation(detail.getLocationId());
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]的库位/仓库编码缺失，无法回写金蝶" + BILL_NAME);
            }
            // 单位必填（2026-11-04 真账套实测报错原文：单据体第1行字段"单位"是必填项）
            String unit = unitOf(detail.getMaterialCode());
            if (oConvertUtils.isEmpty(unit)) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]未维护单位，金蝶要求单位必填，无法回写" + BILL_NAME);
            }
            entries.add(entry(detail, qty, stockCode, unit));
        }
        if (entries.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo()
                    + "]没有可回写的明细行（短装或物料编码为空全部跳过），无需回写金蝶" + BILL_NAME);
        }

        // 受托加工材料入库单头必须有库存组织；取现有配置 default-org-number（101）
        String orgNumber = properties.getDefaultOrgNumber();
        if (oConvertUtils.isEmpty(orgNumber) || orgNumber.trim().isEmpty()) {
            return WmsKingdeeResult.fail("未配置金蝶默认组织（default-org-number），无法回写"
                    + BILL_NAME);
        }
        // 受托方是「客户」：STK_OEMInStock 头字段实查为 FCustId，值取客户编码
        String customer = order.getConsignorCode() == null ? "" : order.getConsignorCode().trim();
        if (customer.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo()
                    + "]缺少受托方客户编码（consignor_code），无法回写金蝶" + BILL_NAME);
        }
        // 货主（客户）/ 保管者（组织）依赖头部字段，明细行在这里补齐
        for (Map<String, Object> entry : entries) {
            applyOwnerAndKeeper(entry, customer, orgNumber.trim());
        }
        // 受托订单号即源单号：WMS 只存 ERP 单号，Push 前必须先换成源单内码
        String sourceBillNo = order.getPurchaseOrderNo() == null
                ? "" : order.getPurchaseOrderNo().trim();
        if (sourceBillNo.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo()
                    + "]缺少受托加工销售订单号（purchase_order_no），无法下推金蝶" + BILL_NAME);
        }

        // 1) 源单内码：Push 的 Ids 只认内码，传编号报「编码值不存在」
        WmsKingdeeResult source = formClient.executeBillQuery(SOURCE_FORM_ID, "FID,FBillNo",
                "FBillNo='" + sourceBillNo.replace("'", "''") + "'", 10);
        if (!source.isSuccess()) {
            return WmsKingdeeResult.fail("金蝶受托加工销售订单查询失败：" + source.getMessage());
        }
        if (source.getRows().isEmpty()) {
            return WmsKingdeeResult.fail("金蝶未找到受托加工销售订单：" + sourceBillNo);
        }
        List<Object> sourceRow = source.getRows().get(0);
        Object sourceFid = sourceRow.isEmpty() ? null : sourceRow.get(0);
        String sourceBillId = sourceFid == null ? "" : String.valueOf(sourceFid).trim();
        if (sourceBillId.isEmpty()) {
            return WmsKingdeeResult.fail("金蝶受托加工销售订单[" + sourceBillNo
                    + "]未返回内码（FID），无法下推" + BILL_NAME);
        }

        // 2) Push：从销售订单下推受托材料入库草稿
        Map<String, Object> pushData = new LinkedHashMap<>();
        pushData.put("RuleId", PUSH_RULE_ID);
        pushData.put("Ids", sourceBillId);
        // Numbers 必须为空：传编号一律报「编码值不存在」
        pushData.put("Numbers", List.of());
        pushData.put("EntryIds", "");
        // CustomParams 必须是字典：传 [] 会报「不能转换为字典类型」
        pushData.put("CustomParams", new LinkedHashMap<String, Object>());
        // 必须为 false：为 true 时金蝶要求另给 TargetFormId，只给 RuleId 会报
        // 「启用默认转换规则，目标单据FormId必填！」（2026-11-04 真账套实测）
        pushData.put("IsEnableDefaultRule", "false");
        pushData.put("IsDraftWhenSaveFail", "true");
        pushData.put("TargetFormId", "");
        pushData.put("TargetBillTypeId", "");
        pushData.put("TargetOrgId", 0);
        log.info("下推金蝶{}({})：源单 {}({})，明细 {} 行", BILL_NAME, formId,
                sourceBillNo, sourceBillId, entries.size());
        WmsKingdeeResult pushed = formClient.push(SOURCE_FORM_ID, pushData);
        if (!pushed.isSuccess()) {
            return WmsKingdeeResult.fail("金蝶下推" + BILL_NAME + "失败：" + pushed.getMessage());
        }
        String targetId = pushed.getId();
        if (oConvertUtils.isEmpty(targetId)) {
            return WmsKingdeeResult.fail("金蝶下推" + BILL_NAME + "未返回草稿内码（源单 "
                    + sourceBillNo + "）");
        }
        String draftNumber = oConvertUtils.isEmpty(pushed.getNumber())
                ? targetId : pushed.getNumber();

        // 3) Save：写进刚下推的草稿（FID 定位，IsDeleteEntry=true 用 WMS 明细覆盖源单分录）
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FID", targetId);
        // 业务日期以审核（上架确认）时间为准，退回创建时间
        model.put("FDate", formatDate(order.getAuditTime() != null
                ? order.getAuditTime() : order.getCreateTime()));
        model.put("FStockOrgId", dict(orgNumber.trim()));
        model.put("FBillTypeID", dict(WmsConstants.KD_BILL_TYPE_CONSIGNED_INBOUND));
        // 基础资料按编码绑定（2026-10-28 实测 {"Id":…} 会报必填项缺失）
        model.put("FCustId", dict(customer));
        model.put("FNote", note(order));
        model.put(ENTRY_KEY, entries);

        WmsKingdeeResult saved = formClient.save(formId, model);
        if (!saved.isSuccess()) {
            log.warn("金蝶下推草稿未完成：{}(FID={})，保留待人工清理，不自动删单", draftNumber, targetId);
            return WmsKingdeeResult.fail("金蝶" + BILL_NAME + "保存失败（草稿 " + draftNumber
                    + "，需人工清理）：" + saved.getMessage());
        }
        String number = oConvertUtils.isEmpty(saved.getNumber()) ? draftNumber : saved.getNumber();

        // 4) Submit：单号取 Save 返回（STCLRK00xxxx）
        WmsKingdeeResult submitted = formClient.submit(formId, List.of(number));
        if (!submitted.isSuccess()) {
            log.warn("金蝶{}{}提交失败，草稿单号 {}", BILL_NAME, number, draftNumber);
            return WmsKingdeeResult.fail("金蝶" + BILL_NAME + "提交失败（草稿 " + number
                    + "，需人工处理）：" + submitted.getMessage());
        }
        log.info("受托入库回写金蝶成功：ERP单号={}，源单={}", number, sourceBillNo);
        return WmsKingdeeResult.ok(submitted.getRaw(), null, number, targetId);
    }

    private Map<String, Object> entry(WmsInboundDetail detail, BigDecimal qty, String stockCode,
                                      String unit) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", dict(detail.getMaterialCode()));
        // 数量必须成对给：只给 FQty 会报「实收数量（基本单位）必须大于 0」
        entry.put("FQty", qty);
        entry.put("FBaseQty", qty);
        // 单位：取物料主数据的单位编码，必须是金蝶 BD_UNIT 的编码（如 Pcs、zhi），不是中文名；
        // 字段名实测为 FUnitId（小写 d），与 PRD_INSTOCK 的 FUnitID 不同
        entry.put("FUnitId", dict(unit));
        // 仓库/库存状态字段名中的 ID 必须大写，小写 FStockId 会被当成没传
        entry.put("FStockID", dict(stockCode));
        entry.put("FStockStatusID", dict(STOCK_STATUS_ID));
        if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
            entry.put("FLot", dict(detail.getBatchNo()));
        }
        return entry;
    }

    /**
     * 货主 / 保管者：受托材料货权属于客户，保管者是受托方组织。
     *
     * <p>这两个字段依赖头部的客户与组织，所以单独在 write 里补齐。
     */
    private void applyOwnerAndKeeper(Map<String, Object> entry, String customer, String orgNumber) {
        entry.put("FOwnerTypeId", OWNER_TYPE_CUSTOMER);
        entry.put("FOwnerId", dict(customer));
        entry.put("FKeeperTypeId", KEEPER_TYPE_ORG);
        entry.put("FKeeperId", dict(orgNumber));
    }

    /** 物料单位（金蝶 BD_UNIT 编码）；查不到返回 null，由调用方显式失败。 */
    private String unitOf(String materialCode) {
        if (oConvertUtils.isEmpty(materialCode)) {
            return null;
        }
        List<WmsMaterial> found = materialService.list(Wrappers.<WmsMaterial>query()
                .eq("material_code", materialCode.trim()).last("LIMIT 1"));
        if (found == null || found.isEmpty() || found.get(0) == null) {
            return null;
        }
        String unit = found.get(0).getUnit();
        return unit == null ? null : unit.trim();
    }

    private BigDecimal qtyOf(WmsInboundDetail detail) {
        return detail.getActualQuantity() != null ? detail.getActualQuantity() : detail.getQuantity();
    }

    private String note(WmsInboundOrder order) {
        StringBuilder sb = new StringBuilder("WMS受托入库:");
        sb.append(order.getOrderNo());
        if (oConvertUtils.isNotEmpty(order.getPurchaseOrderNo())) {
            sb.append("；受托订单:").append(order.getPurchaseOrderNo());
        }
        String text = sb.toString();
        return text.length() <= NOTE_MAX_LENGTH ? text : text.substring(0, NOTE_MAX_LENGTH);
    }

    private String requireFormId() {
        String formId = configService.getString(FORM_KEY.getKey(), "");
        if (oConvertUtils.isEmpty(formId) || formId.trim().isEmpty()) {
            throw new IllegalStateException("金蝶单据[" + BILL_NAME + "]未配置 formId（配置项 "
                    + FORM_KEY.getKey() + "）");
        }
        return formId.trim();
    }

    /** 基础资料字典：{FNumber: "编码"}。 */
    private Map<String, Object> dict(String number) {
        return Map.of("FNumber", number);
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }
}
