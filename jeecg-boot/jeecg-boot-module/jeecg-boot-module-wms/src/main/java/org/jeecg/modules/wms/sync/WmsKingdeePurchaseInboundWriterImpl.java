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
 * @Description: 采购入库单回写（V2.4 §3.2 段差棒直入）。
 *
 * <p><b>formId/字段需控制台确认</b>：金蝶采购入库单的 FormId、单据类型、必填基础资料随
 * 账套不同，本类只搭报文骨架，字段名以 BOS 在线控制台实测为准。
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准（2026-10-28 决策）；</li>
 *   <li>只传业务字段：日期、供应商、物料编码、实收数量、批次、备注；</li>
 *   <li>数量取「实收数量」，为空回退「应收数量」；<b>短装行（数量 0）跳过</b>，
 *       负数仍报错；只有全部行都不可回写时才整单失败——0 数量行让整单失败会造成
 *       「库存已入账、ERP 未回写」；</li>
 *   <li>日期取审核时间（{@code auditTime}），为空回退创建时间；</li>
 *   <li>明细为空显式失败，不发空报文。</li>
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
public class WmsKingdeePurchaseInboundWriterImpl implements IWmsKingdeePurchaseInboundWriter {

    private static final String BILL_NAME = "采购入库单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_PURCHASE_INBOUND;
    /** 备注长度上限：金蝶 FNote 超长会整单保存失败。 */
    private static final int NOTE_MAX_LENGTH = 500;

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsInboundDetailMapper detailMapper;
    private final WmsKingdeeProperties properties;

    public WmsKingdeePurchaseInboundWriterImpl(WmsKingdeeFormClient formClient,
                                               IWmsSystemConfigService configService,
                                               WmsInboundDetailMapper detailMapper,
                                               WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.configService = configService;
        this.detailMapper = detailMapper;
        this.properties = properties;
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

        List<WmsInboundDetail> details = detailMapper.selectList(
                new QueryWrapper<WmsInboundDetail>().eq("inbound_id", order.getId()));
        if (details == null || details.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]没有明细，无法回写金蝶"
                    + BILL_NAME);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (WmsInboundDetail detail : details) {
            // 数量校验必须排在「空物料跳过」之前：否则负数量/空数量会被跳过掩盖
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
            if (oConvertUtils.isEmpty(detail.getMaterialCode())) {
                log.warn("入库单[{}]存在物料编码为空的明细行，跳过回写（明细 id={}）",
                        order.getOrderNo(), detail.getId());
                continue;
            }
            entries.add(entry(detail, qty));
        }
        if (entries.isEmpty()) {
            return WmsKingdeeResult.fail("入库单[" + order.getOrderNo()
                    + "]没有可回写的明细行（短装或物料编码为空全部跳过），无需回写金蝶" + BILL_NAME);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期以审核（上架确认）时间为准，退回创建时间
        model.put("FDate", formatDate(order.getAuditTime() != null
                ? order.getAuditTime() : order.getCreateTime()));
        if (oConvertUtils.isNotEmpty(order.getSupplierCode())) {
            // 供应商：基础资料按编码绑定（2026-10-28 实测 {"Id":…} 会报必填项缺失）
            model.put("FSupplierId", dict(order.getSupplierCode()));
        }
        model.put("FNote", note(order));
        model.put("FEntity", entries);

        log.info("回写金蝶{}({})：WMS 单号 {}，明细 {} 行", BILL_NAME, formId, order.getOrderNo(),
                entries.size());
        return formClient.save(formId, model);
    }

    private Map<String, Object> entry(WmsInboundDetail detail, BigDecimal qty) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", dict(detail.getMaterialCode()));
        entry.put("FQty", qty);
        if (oConvertUtils.isNotEmpty(detail.getBatchNo())) {
            entry.put("FLot", dict(detail.getBatchNo()));
        }
        return entry;
    }

    private BigDecimal qtyOf(WmsInboundDetail detail) {
        return detail.getActualQuantity() != null ? detail.getActualQuantity() : detail.getQuantity();
    }

    private String note(WmsInboundOrder order) {
        StringBuilder sb = new StringBuilder("WMS采购入库:");
        sb.append(order.getOrderNo());
        if (oConvertUtils.isNotEmpty(order.getPurchaseOrderNo())) {
            sb.append("；采购订单:").append(order.getPurchaseOrderNo());
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
