package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
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
 * <p><b>formId/字段需控制台确认</b>：金蝶受托加工材料入库单的 FormId、单据类型、必填
 * 基础资料随账套不同，本类只搭报文骨架，字段名以 BOS 在线控制台实测为准。
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准（2026-10-28 决策）；</li>
 *   <li>只传业务字段：日期、受托方、物料编码、实收数量、批次、备注；</li>
 *   <li>数量取「实收数量」，为空回退「应收数量」——上架前调用时只有应收是常态；</li>
 *   <li>明细为空显式失败，不发空报文（金蝶只会报「分录不能为空」，本地报错更好排查）。</li>
 * </ul>
 *
 * <p>本类不判断 {@code wms.sync.erp.kingdee.enabled}，也不返回 mock 成功：
 * 开关由调用方在发起前判断，骨架层不制造「假成功」。
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

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsInboundDetailMapper detailMapper;

    public WmsKingdeeConsignedInboundWriterImpl(WmsKingdeeFormClient formClient,
                                                IWmsSystemConfigService configService,
                                                WmsInboundDetailMapper detailMapper) {
        this.formClient = formClient;
        this.configService = configService;
        this.detailMapper = detailMapper;
    }

    @Override
    public WmsKingdeeResult write(WmsInboundOrder order) {
        if (order == null || oConvertUtils.isEmpty(order.getId())) {
            throw new IllegalArgumentException(BILL_NAME + "回写缺少 WMS 入库单（id 为空）");
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
            BigDecimal qty = qtyOf(detail);
            if (qty == null || qty.signum() <= 0) {
                return WmsKingdeeResult.fail("入库单[" + order.getOrderNo() + "]物料["
                        + detail.getMaterialCode() + "]数量为空或非正数，无法回写金蝶" + BILL_NAME);
            }
            entries.add(entry(detail, qty));
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("FDate", formatDate(order.getCreateTime()));
        if (oConvertUtils.isNotEmpty(order.getSupplierCode())) {
            // 受托方：基础资料按编码绑定（2026-10-28 实测 {"Id":…} 会报必填项缺失）
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
