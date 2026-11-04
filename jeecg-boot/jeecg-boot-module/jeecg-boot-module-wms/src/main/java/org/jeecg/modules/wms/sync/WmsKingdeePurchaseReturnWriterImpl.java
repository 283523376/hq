package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
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
 * @Description: 采购退货单回写（V2.4 §3.3 受托不良退货）。
 *
 * <p><b>formId/字段需控制台确认</b>：金蝶采购退货单（供应链→采购管理）的 FormId、
 * 单据类型、必填基础资料随账套不同，本类只搭报文骨架，字段名以 BOS 在线控制台实测为准。
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准；</li>
 *   <li>只传业务字段：日期、受托方、物料编码、退货数量、批次、备注（来源单号/不良原因放备注，
 *       不猜测尚未确认的源单关联字段）；</li>
 *   <li>受托方（供应商）由调用方按 {@code record.sourceBillNo} 反查入库单后作为
 *       {@code supplierNumber} 传入——{@code wms_defect_record} 本身不存受托方；</li>
 *   <li>日期取记录创建时间，不取执行瞬间（补跑/重试不会写错日期）；</li>
 *   <li>处置方式必须是 RETURN，否则抛错（防止报废记录误开退货单）；</li>
 *   <li>数量为空或非正数显式失败。</li>
 * </ul>
 *
 * <p>{@link #isEnabled()} 暴露 {@code wms.sync.erp.kingdee.enabled} 供调用方发起前判断；
 * Writer 不因开关关闭而返回 mock 成功。
 *
 * <p>{@link #existsByReference(String)} 供幂等查重：按备注参考号查询金蝶是否已有该退货单，
 * 查询失败抛错而不是返回 false——把查询失败当成「不存在」会让重试重复开单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsKingdeePurchaseReturnWriterImpl implements IWmsKingdeePurchaseReturnWriter {

    private static final String BILL_NAME = "采购退货单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_PURCHASE_RETURN;
    /** 备注长度上限：金蝶 FNote 超长会整单保存失败。 */
    private static final int NOTE_MAX_LENGTH = 500;

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsKingdeeProperties properties;

    public WmsKingdeePurchaseReturnWriterImpl(WmsKingdeeFormClient formClient,
                                              IWmsSystemConfigService configService,
                                              WmsKingdeeProperties properties) {
        this.formClient = formClient;
        this.configService = configService;
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public WmsKingdeeResult write(WmsDefectRecord record, String supplierNumber) {
        if (record == null) {
            throw new IllegalArgumentException(BILL_NAME + "回写缺少不良品记录");
        }
        if (!WmsConstants.DEFECT_DISPOSAL_RETURN.equals(record.getDisposalType())) {
            throw new IllegalArgumentException("不良品记录[" + record.getId() + "]处置方式为["
                    + record.getDisposalType() + "]，不能回写" + BILL_NAME + "（要求 disposal_type=RETURN）");
        }
        // formId 必须最先校验：未配置时不做任何报文组装
        String formId = requireFormId();

        BigDecimal qty = record.getQty();
        if (qty == null || qty.signum() <= 0) {
            return WmsKingdeeResult.fail("不良品记录[" + record.getId() + "]数量为空或非正数，无法回写金蝶"
                    + BILL_NAME);
        }
        if (oConvertUtils.isEmpty(record.getMaterialCode())) {
            return WmsKingdeeResult.fail("不良品记录[" + record.getId() + "]缺少物料编码，无法回写金蝶"
                    + BILL_NAME);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", Map.of("FNumber", record.getMaterialCode()));
        entry.put("FQty", qty);
        if (oConvertUtils.isNotEmpty(record.getBatchNo())) {
            entry.put("FLot", Map.of("FNumber", record.getBatchNo()));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry);

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期用记录创建时间（不取执行瞬间的 new Date()：补跑/重试会写错日期）
        model.put("FDate", formatDate(record.getCreateTime()));
        if (oConvertUtils.isNotEmpty(supplierNumber)) {
            // 受托方（基础资料按编码绑定）；不良品记录不存受托方，由调用方反查入库单后传入
            model.put("FSupplierId", Map.of("FNumber", supplierNumber.trim()));
        }
        model.put("FNote", note(record));
        model.put("FEntity", entries);

        log.info("回写金蝶{}({})：不良记录 {}，来源单 {}，受托方 {}", BILL_NAME, formId, record.getId(),
                record.getSourceBillNo(), supplierNumber);
        return formClient.save(formId, model);
    }

    @Override
    public boolean existsByReference(String ref) {
        if (oConvertUtils.isEmpty(ref) || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("按参考号查重" + BILL_NAME + "必须提供参考号");
        }
        String formId = requireFormId();
        String filter = "FNote='" + ref.trim().replace("'", "''") + "'";
        WmsKingdeeResult result = formClient.executeBillQuery(formId, "FBillNo", filter, 1);
        if (!result.isSuccess()) {
            // 查重失败不能当成「不存在」：那会在重试时重复开单
            throw new IllegalStateException("金蝶" + BILL_NAME + "查重失败（参考号 " + ref + "）："
                    + result.getMessage());
        }
        return !result.getRows().isEmpty();
    }

    private String note(WmsDefectRecord record) {
        StringBuilder sb = new StringBuilder("WMS受托不良退货");
        if (oConvertUtils.isNotEmpty(record.getSourceBillNo())) {
            sb.append("；来源单:").append(record.getSourceBillNo());
        }
        if (oConvertUtils.isNotEmpty(record.getReason())) {
            sb.append("；原因:").append(record.getReason());
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

    private String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd").format(date == null ? new Date() : date);
    }
}
