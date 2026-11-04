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
import java.util.regex.Pattern;

/**
 * @Description: 采购退料单回写（V2.4 §3.3 受托不良退货）。
 *
 * <p><b>字段依据（2026-11-04 测试账套 6a9aaaadaf7ee6 实查 PUR_MRB）</b>：
 * <ul>
 *   <li>头：{@code FSupplierId.FNumber}（View 里实查的键名为 {@code SUPPLIERID}）、
 *       {@code FStockOrgId} / {@code FStockId.FNumber}、{@code FBillTypeID.FNumber}
 *       （{@link WmsConstants#KD_BILL_TYPE_PURCHASE_RETURN}）、{@code FDate}、{@code FNote}；</li>
 *   <li>数量字段：<b>{@code FRMREALQTY}（实退数量）/ {@code FRMMUSTQTY}（应退数量）</b>，
 *       实查 {@code FQty} 不存在；本类写实退数量；</li>
 *   <li>批次：{@code FLot.FNumber}。</li>
 * </ul>
 *
 * <p><b>尚未真实试写验证（按 View / K3Cloud 惯例推导）</b>：
 * <ol>
 *   <li>分录实体键 {@code FPUR_MRBENTRY}——View 显示的是 {@code PUR_MRBENTRY}，
 *       Save 侧按 K3Cloud 惯例加 {@code F} 前缀，**需一次试写确认**；</li>
 *   <li>头字段名 {@code FSupplierId} 与 View 的 {@code SUPPLIERID} 大小写/形态差异——
 *       供货商字段是否存在别名，试写报错时按控制台返回的键名修正；</li>
 *   <li>退料单是否必须带 {@code FMRType} / {@code FMRDeptId} 等 ERP 侧强制字段。</li>
 * </ol>
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准；</li>
 *   <li>只传业务字段：日期、受托方（客户）、物料编码、退货数量、批次、仓库、备注；
 *       备注<b>恰好</b>为 {@code 来源单:<ref>}——它同时是幂等定位符，不拼原因等可变内容；</li>
 *   <li>受托方由调用方按 {@code record.sourceBillNo} 反查入库单的 {@code consignor_id}
 *       后作为 {@code customerNumber} 传入——{@code wms_defect_record} 本身不存受托方；</li>
 *   <li>日期取记录创建时间，不取执行瞬间（补跑/重试不会写错日期）；</li>
 *   <li>处置方式必须是 RETURN，否则抛错（防止报废记录误开退货单）；</li>
 *   <li>数量为空或非正数显式失败。</li>
 * </ul>
 *
 * <p>{@link #isEnabled()} 暴露 {@code wms.sync.erp.kingdee.enabled} 供调用方发起前判断；
 * Writer 不因开关关闭而返回 mock 成功。
 *
 * <p>{@link #existsByReference(String)} 供幂等查重：用<b>等值</b>匹配
 * {@code FNote='来源单:<ref>'} 查询金蝶是否已有该退货单。不用 LIKE：金蝶 FilterString
 * 对 LIKE 的支持与底层方言转义层数均未验证。查询失败抛错而不是返回 false——
 * 把查询失败当成「不存在」会让重试重复开单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsKingdeePurchaseReturnWriterImpl implements IWmsKingdeePurchaseReturnWriter {

    private static final String BILL_NAME = "采购退货单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_PURCHASE_RETURN;
    /** 幂等定位符（来源单号）的合法形态：WMS 单号即字母/数字/下划线/连字符。 */
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** 分录实体键：View 显示 PUR_MRBENTRY，Save 侧按 K3Cloud 惯例加 F 前缀（待试写确认）。 */
    static final String ENTRY_KEY = "FPUR_MRBENTRY";

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
    public WmsKingdeeResult write(WmsDefectRecord record, String customerNumber, String warehouseCode) {
        if (record == null) {
            throw new IllegalArgumentException(BILL_NAME + "回写缺少不良品记录");
        }
        if (!WmsConstants.DEFECT_DISPOSAL_RETURN.equals(record.getDisposalType())) {
            throw new IllegalArgumentException("不良品记录[" + record.getId() + "]处置方式为["
                    + record.getDisposalType() + "]，不能回写" + BILL_NAME + "（要求 disposal_type=RETURN）");
        }
        // formId 必须最先校验：未配置时不做任何报文组装
        String formId = requireFormId();
        // 幂等定位符：与 existsByReference 共用同一套 trim + 字符集校验，保证写/查口径一致
        String reference = requireReference(record.getSourceBillNo());

        BigDecimal qty = record.getQty();
        if (qty == null || qty.signum() <= 0) {
            return WmsKingdeeResult.fail("不良品记录[" + record.getId() + "]数量为空或非正数，无法回写金蝶"
                    + BILL_NAME);
        }
        if (oConvertUtils.isEmpty(record.getMaterialCode())) {
            return WmsKingdeeResult.fail("不良品记录[" + record.getId() + "]缺少物料编码，无法回写金蝶"
                    + BILL_NAME);
        }
        // 仓库是金蝶退料单必填：解析不到就显式失败，不发缺 FStockId 的报文
        String stockCode = warehouseCode == null ? "" : warehouseCode.trim();
        if (stockCode.isEmpty()) {
            return WmsKingdeeResult.fail("受托不良记录[" + record.getId()
                    + "]来源入库单无法解析退货仓库编码，无法回写金蝶" + BILL_NAME);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", Map.of("FNumber", record.getMaterialCode()));
        // PUR_MRB 实查数量字段为 FRMREALQTY（实退数量），FQty 不存在
        entry.put("FRMREALQTY", qty);
        if (oConvertUtils.isNotEmpty(record.getBatchNo())) {
            entry.put("FLot", Map.of("FNumber", record.getBatchNo()));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry);

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期用记录创建时间（不取执行瞬间的 new Date()：补跑/重试会写错日期）
        model.put("FDate", formatDate(record.getCreateTime()));
        model.put("FBillTypeID", Map.of("FNumber", WmsConstants.KD_BILL_TYPE_PURCHASE_RETURN));
        String supplier = customerNumber == null ? "" : customerNumber.trim();
        if (!supplier.isEmpty()) {
            // 受托方（基础资料按编码绑定）；不良品记录不存受托方，由调用方反查入库单的客户后传入。
            // 先 trim 再判空：纯空格不得发成 {FNumber: ""}，金蝶会报必填项缺失
            model.put("FSupplierId", Map.of("FNumber", supplier));
        }
        String orgNumber = properties.getDefaultOrgNumber();
        if (oConvertUtils.isEmpty(orgNumber) || orgNumber.trim().isEmpty()) {
            return WmsKingdeeResult.fail("未配置金蝶默认组织（default-org-number），无法回写" + BILL_NAME);
        }
        model.put("FStockOrgId", Map.of("FNumber", orgNumber.trim()));
        model.put("FStockId", Map.of("FNumber", stockCode));
        // 备注恰好为「来源单:<ref>」——它同时是幂等定位符，不能拼原因等可变内容
        model.put("FNote", "来源单:" + reference);
        model.put(ENTRY_KEY, entries);

        log.info("回写金蝶{}({})：不良记录 {}，来源单 {}，受托方 {}，仓库 {}", BILL_NAME, formId,
                record.getId(), reference, supplier, stockCode);
        return formClient.save(formId, model);
    }

    @Override
    public boolean existsByReference(String ref) {
        String reference = requireReference(ref);
        String formId = requireFormId();
        // 等值匹配：写入的 FNote 恰好是「来源单:<ref>」，两边同一口径；
        // LIKE 在金蝶 FilterString 下的可用性与方言转义层数未验证，不采用
        String filter = "FNote='" + "来源单:" + reference.replace("'", "''") + "'";
        WmsKingdeeResult result = formClient.executeBillQuery(formId, "FBillNo", filter, 1);
        if (!result.isSuccess()) {
            // 查重失败不能当成「不存在」：那会在重试时重复开单
            throw new IllegalStateException("金蝶" + BILL_NAME + "查重失败（参考号 " + reference + "）："
                    + result.getMessage());
        }
        return !result.getRows().isEmpty();
    }

    /**
     * 参考号校验（write 与 existsByReference 共用）：trim 后必须匹配
     * {@code ^[A-Za-z0-9_-]{1,64}$}。
     *
     * <p>字符集收紧到 WMS 单号的实际形态：引号、通配符、控制字符、反斜杠一概不进来，
     * 查询条件只需做单引号加倍，不存在方言/转义层数问题。
     */
    private String requireReference(String ref) {
        if (oConvertUtils.isEmpty(ref) || ref.trim().isEmpty()) {
            throw new IllegalArgumentException(BILL_NAME + "回写/查重必须提供来源单号");
        }
        String value = ref.trim();
        if (!REFERENCE_PATTERN.matcher(value).matches()) {
            // 不回显原值：脏数据可能含控制字符，会把日志/异常文案渲染乱
            throw new IllegalArgumentException("来源单号不合法（要求 ^[A-Za-z0-9_-]{1,64}$），拒绝写入金蝶"
                    + BILL_NAME);
        }
        return value;
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
