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
 * @Description: 采购收料单回写（V2.4 §3.2 段差棒直入）。
 *
 * <p><b>为什么是收料单不是入库单</b>：101 账套内全部原材料都启用了来料检验，
 * 采购入库单（{@code STK_InStock}）不允许由外部直接创建——直开会被来料检验规则拦下。
 * 正确的链路是 WMS 回写<b>采购收料单</b>（{@code PUR_ReceiveBill}），
 * 金蝶在来料检验通过后从收料单下推生成采购入库单（用户 2026-11-04 拍板，路线 A）。
 *
 * <p><b>字段依据（2026-11-04 测试账套 6a9aaaadaf7ee6 实测，生成 CGSL016641）</b>：
 * <ul>
 *   <li>头：{@code FStockOrgId}、{@code FBillTypeID}（{@link WmsConstants#KD_BILL_TYPE_PURCHASE_RECEIVE}）、
 *       {@code FDate}、{@code FSupplierId}、{@code FOwnerTypeIdHead}/{@code FOwnerIdHead}、
 *       {@code FPurOrgId}、{@code FNote}（采购入库单没有头备注，别搞混）；</li>
 *   <li>分录实体键：{@code FDetailEntity}（不是 {@code FBillEntry}、也不是 {@code FEntity}）；</li>
 *   <li>数量：{@code FActReceiveQty} + {@code FReceiveQty} + {@code FReceiveBaseQty} 三个给同一数量；</li>
 *   <li>单位：{@code FUnitId}、{@code FPriceUnitId}、<b>{@code FStockUnitID}</b>（收料单位，ID 大写）；</li>
 *   <li>仓库 {@code FStockID}、收料单位 {@code FStockUnitID} 的 <b>ID 大写</b>，
 *       而库存状态是 {@code FStockStatusId}（小写 d）、货主 {@code FOwnerId}（小写 d）——
 *       大小写写错会被金蝶当成没传。</li>
 * </ul>
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准（2026-10-28 决策）；</li>
 *   <li>只传业务字段：日期、供应商、物料编码、实收数量、批次、仓库、单位、备注；</li>
 *   <li>数量取「实收数量」，为空回退「应收数量」；<b>短装行（数量 0）跳过</b>，
 *       负数仍报错；只有全部行都不可回写时才整单失败——0 数量行让整单失败会造成
 *       「库存已入账、ERP 未回写」；</li>
 *   <li>物料编码为空的行跳过；仓库/单位解析不到、组织未配置、formId 未配置一律显式失败；</li>
 *   <li>日期取审核时间（{@code auditTime}），为空回退创建时间；</li>
 *   <li>明细为空显式失败，不发空报文；</li>
 *   <li>金蝶返回的错误原文原样透传，不包装成笼统的「保存失败」。</li>
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
public class WmsKingdeePurchaseReceiveWriterImpl implements IWmsKingdeePurchaseReceiveWriter {

    private static final String BILL_NAME = "采购收料单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_PURCHASE_INBOUND;
    /** 备注长度上限：金蝶 FNote 超长会整单保存失败。 */
    private static final int NOTE_MAX_LENGTH = 500;
    /** 分录实体键：真账套实测为 FDetailEntity（不是 FBillEntry / FEntity）。 */
    static final String ENTRY_KEY = "FDetailEntity";
    /**
     * 单价占位：收料单要求 {@code FPrice}/{@code FTaxPrice} 大于 0（填 0 报错），
     * WMS 不存采购价，用 1 元占位；真实单价以金蝶采购订单/收料单为准。
     */
    private static final BigDecimal PLACEHOLDER_PRICE = BigDecimal.ONE;

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsInboundDetailMapper detailMapper;
    private final WmsKingdeeProperties properties;
    private final WmsWarehouseCodeResolver warehouseResolver;
    private final IWmsMaterialService materialService;

    public WmsKingdeePurchaseReceiveWriterImpl(WmsKingdeeFormClient formClient,
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
        if (!WmsConstants.INBOUND_SOURCE_PURCHASE.equals(order.getSourceType())) {
            throw new IllegalArgumentException("入库单[" + order.getOrderNo() + "]来源类型为["
                    + order.getSourceType() + "]，不能回写" + BILL_NAME
                    + "（要求 source_type=PURCHASE）");
        }
        // formId 必须最先校验：未配置时连明细都不该查
        String formId = requireFormId();
        // 收料单头必须有库存组织/货主组织/采购组织；取现有配置 default-org-number（101）
        String orgNumber = trim(properties.getDefaultOrgNumber());
        if (orgNumber.isEmpty()) {
            return WmsKingdeeResult.fail("未配置金蝶默认组织（default-org-number），无法回写"
                    + BILL_NAME);
        }

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
                // 短装行：库存侧已按跳过处理，ERP 也不应出现 0 数量分录；
                // 整单因此失败会让「库存已入账、ERP 未回写」对不上账
                log.info("入库单[{}]物料[{}]数量为 0（短装），跳过该行回写",
                        order.getOrderNo(), detail.getMaterialCode());
                continue;
            }
            // 仓库逐行解析：不同物料可能上架到不同库区（明细的 location_id 上架时写入）。
            // 解析不到必须显式失败——FStockID 是金蝶必填，省略只会把错误推迟到 ERP 保存。
            String stockCode = warehouseResolver.byLocation(detail.getLocationId());
            if (oConvertUtils.isEmpty(stockCode)) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]的库位/仓库编码缺失，无法回写金蝶" + BILL_NAME);
            }
            // 单位必填（真账套实测报错原文：单据体第1行字段"单位"是必填项）
            String unit = unitOf(detail.getMaterialCode());
            if (oConvertUtils.isEmpty(unit)) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]未维护单位，金蝶要求单位必填，无法回写" + BILL_NAME);
            }
            entries.add(entry(detail, qty, stockCode, unit, orgNumber));
        }
        if (entries.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo()
                    + "]没有可回写的明细行（短装或物料编码为空全部跳过），无需回写金蝶" + BILL_NAME);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期以审核（上架确认）时间为准，退回创建时间
        model.put("FDate", formatDate(order.getAuditTime() != null
                ? order.getAuditTime() : order.getCreateTime()));
        model.put("FStockOrgId", dict(orgNumber));
        model.put("FBillTypeID", dict(WmsConstants.KD_BILL_TYPE_PURCHASE_RECEIVE));
        String supplier = trim(order.getSupplierCode());
        if (!supplier.isEmpty()) {
            // 供应商：基础资料按编码绑定（2026-10-28 实测 {"Id":…} 会报必填项缺失）；
            // 先 trim 再判空，纯空格不得发成 {FNumber: ""}。
            // 字段名是小写 d（FSupplierId）：2026-11-04 金蝶报错 FieldName 直接给出，
            // 大写 FSupplierID 会被当成没传（与受托单 STK_OEMInStock 的"大写 ID"规律相反）
            model.put("FSupplierId", dict(supplier));
            // 供货方/结算方/付款方：金蝶界面新增时由供应商自动带出，API 需显式给
            model.put("FSupplyId", dict(supplier));
            model.put("FSettleId", dict(supplier));
            model.put("FChargeId", dict(supplier));
        }
        // 货主是组织：字段名小写 d（FOwnerIdHead），采购组织同为本组织
        model.put("FOwnerTypeIdHead", WmsConstants.KD_OWNER_TYPE_ORG);
        model.put("FOwnerIdHead", dict(orgNumber));
        model.put("FPurOrgId", dict(orgNumber));
        // 需求组织：与收料组织一致（金蝶界面默认带出）
        model.put("FDemandOrgId", dict(orgNumber));
        // 采购业务类型：不传会导致头字段校验错位
        model.put("FBusinessType", WmsConstants.KD_BUSINESS_TYPE_PURCHASE);
        model.put("FNote", note(order));
        // 财务信息子实体：结算币别是硬必填，缺了报「结算币别是必填项」；
        // 且该子实体缺失会连带把整个 Model 的校验错位成「供应商/货主必填」（实测）
        Map<String, Object> finance = new LinkedHashMap<>();
        finance.put("FEntryId", 0);
        finance.put("FSettleOrgId", dict(orgNumber));
        finance.put("FSettleCurrId", dict(WmsConstants.KD_CURRENCY_CNY));
        finance.put("FLocalCurrId", dict(WmsConstants.KD_CURRENCY_CNY));
        finance.put("FIsIncludedTax", "false");
        finance.put("FISPRICEEXCLUDETAX", "false");
        finance.put("FExchangeRate", 1);
        finance.put("FAllDisCount", 0);
        model.put("FinanceEntity", finance);
        // 金蝶 Save 对「模板键缺失」敏感：缺键会导致整个 Model 校验错位，
        // 报出来的却是无关字段的「必填项」（2026-11-05 实测，13 轮误判的根因）
        model.putIfAbsent("FReceiveDeptId", dict(""));
        model.putIfAbsent("FStockGroupId", dict(""));
        model.putIfAbsent("FReceiverId", dict(""));
        model.putIfAbsent("FCorrespondOrgId", dict(""));
        model.putIfAbsent("FPurDeptId", dict(""));
        model.putIfAbsent("FPurGroupId", dict(""));
        model.putIfAbsent("FPurchaserId", dict(""));
        model.put(ENTRY_KEY, entries);

        log.info("回写金蝶{}({})：WMS 单号 {}，明细 {} 行，供应商={}", BILL_NAME, formId,
                order.getOrderNo(), entries.size(), supplier);
        return formClient.save(formId, model);
    }

    private Map<String, Object> entry(WmsInboundDetail detail, BigDecimal qty, String stockCode,
                                      String unit, String orgNumber) {
        Map<String, Object> entry = new LinkedHashMap<>();
        // 分录内码必须显式给 0：缺了它金蝶会解析错位，把整单头字段当成没传，
        // 报出来的却是「供应商/货主必填」——误导性极强（2026-11-04 真账套对照实验）
        entry.put("FEntryID", 0);
        entry.put("FMaterialId", dict(detail.getMaterialCode()));
        // 数量：实收数量与计价/库存数量给同一值（真账套实测三个字段都收）
        entry.put("FActReceiveQty", qty);
        entry.put("FReceiveQty", qty);
        entry.put("FReceiveBaseQty", qty);
        // 单位：取物料主数据的单位编码，必须是金蝶 BD_UNIT 的编码（如 Pcs、zhi），不是中文名
        entry.put("FUnitId", dict(unit));
        entry.put("FPriceUnitId", dict(unit));
        // 收料单位字段名里的 ID 必须大写，小写会被当成没传
        entry.put("FStockUnitID", dict(unit));
        // 仓库同样是大写 ID
        entry.put("FStockID", dict(stockCode));
        // 库存状态是 FStockStatusId（小写 d），与上面两个相反
        entry.put("FStockStatusId", dict(WmsConstants.STOCK_STATUS_AVAILABLE));
        // 货主：自有库存货权属于本组织
        entry.put("FOwnerTypeId", WmsConstants.KD_OWNER_TYPE_ORG);
        entry.put("FOwnerId", dict(orgNumber));
        entry.put("FPrice", PLACEHOLDER_PRICE);
        entry.put("FTaxPrice", PLACEHOLDER_PRICE);
        if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
            entry.put("FLot", dict(detail.getBatchNo()));
        }
        return entry;
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
        return trim(found.get(0).getUnit());
    }

    private BigDecimal qtyOf(WmsInboundDetail detail) {
        return detail.getActualQuantity() != null ? detail.getActualQuantity() : detail.getQuantity();
    }

    private String note(WmsInboundOrder order) {
        StringBuilder sb = new StringBuilder("WMS-RECEIVE:");
        sb.append(order.getOrderNo());
        if (oConvertUtils.isNotEmpty(order.getPurchaseOrderNo())) {
            sb.append(";PO:").append(order.getPurchaseOrderNo());
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }
}
