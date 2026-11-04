package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
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
 * <p><b>字段依据（2026-11-04 测试账套 6a9aaaadaf7ee6 实查 STK_OEMInStock）</b>：
 * <ul>
 *   <li>头：<b>{@code FCustId.FNumber}（客户，不是 FSupplierId）</b>、
 *       {@code FStockOrgId.FNumber}、{@code FBillTypeID.FNumber}
 *       （{@link WmsConstants#KD_BILL_TYPE_CONSIGNED_INBOUND}）、{@code FDate}、{@code FNote}；</li>
 *   <li>数量字段：{@code FQty}（这个存在，与采购入库单的 FRealQty 不同）；</li>
 *   <li>仓库：{@code FStockId.FNumber}、批次 {@code FLot.FNumber}。</li>
 * </ul>
 *
 * <p><b>尚未真实试写验证（按 View / K3Cloud 惯例推导）</b>：
 * <ol>
 *   <li>分录实体键 {@code FOEMInStockEntry}——View 显示的是 {@code OEMInStockEntry}，
 *       Save 侧按 K3Cloud 惯例加 {@code F} 前缀，**需一次试写确认**；</li>
 *   <li>{@code FCustId} 按编码字典 {@code {FNumber: customer_code}} 绑定——金蝶 Save 的
 *       基础资料绑定方式在采购入库单上已实测走编码，但受托单据未单独验证；</li>
 *   <li>{@code FStockOrgId} 用配置 {@code default-org-number}（101）——受托单是否允许
 *       跨组织、是否还需 {@code FOwnerId} 等字段，试写后才能定。</li>
 * </ol>
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准（2026-10-28 决策）；</li>
 *   <li>只传业务字段：日期、受托方（客户）、物料编码、实收数量、批次、仓库、备注；</li>
 *   <li>数量取「实收数量」，为空回退「应收数量」——上架前调用时只有应收是常态；
 *       <b>短装行（数量 0）跳过</b>，负数仍报错；只有全部行都不可回写时才整单失败；</li>
 *   <li>日期取审核时间（{@code auditTime}），为空回退创建时间；</li>
 *   <li>明细为空显式失败，不发空报文（金蝶只会报「分录不能为空」，本地报错更好排查）。</li>
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
    /** 分录实体键：View 显示 OEMInStockEntry，Save 侧按 K3Cloud 惯例加 F 前缀（待试写确认）。 */
    static final String ENTRY_KEY = "FOEMInStockEntry";

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsInboundDetailMapper detailMapper;
    private final WmsKingdeeProperties properties;
    private final WmsWarehouseCodeResolver warehouseResolver;

    public WmsKingdeeConsignedInboundWriterImpl(WmsKingdeeFormClient formClient,
                                                IWmsSystemConfigService configService,
                                                WmsInboundDetailMapper detailMapper,
                                                WmsKingdeeProperties properties,
                                                WmsWarehouseCodeResolver warehouseResolver) {
        this.formClient = formClient;
        this.configService = configService;
        this.detailMapper = detailMapper;
        this.properties = properties;
        this.warehouseResolver = warehouseResolver;
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
            entries.add(entry(detail, qty, stockCode));
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

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期以审核（上架确认）时间为准，退回创建时间
        model.put("FDate", formatDate(order.getAuditTime() != null
                ? order.getAuditTime() : order.getCreateTime()));
        model.put("FStockOrgId", dict(orgNumber.trim()));
        model.put("FBillTypeID", dict(WmsConstants.KD_BILL_TYPE_CONSIGNED_INBOUND));
        // 基础资料按编码绑定（2026-10-28 实测 {"Id":…} 会报必填项缺失）
        model.put("FCustId", dict(customer));
        model.put("FNote", note(order));
        model.put(ENTRY_KEY, entries);

        log.info("回写金蝶{}({})：WMS 单号 {}，明细 {} 行", BILL_NAME, formId, order.getOrderNo(),
                entries.size());
        return formClient.save(formId, model);
    }

    private Map<String, Object> entry(WmsInboundDetail detail, BigDecimal qty, String stockCode) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", dict(detail.getMaterialCode()));
        // STK_OEMInStock 的数量字段就是 FQty（与 STK_InStock 的 FRealQty 不同）
        entry.put("FQty", qty);
        entry.put("FStockId", dict(stockCode));
        if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
            entry.put("FLot", dict(detail.getBatchNo()));
        }
        return entry;
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
