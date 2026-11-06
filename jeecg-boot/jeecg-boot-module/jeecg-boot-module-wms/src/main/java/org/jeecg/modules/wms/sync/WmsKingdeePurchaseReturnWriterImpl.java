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
 *       （{@link WmsConstants#KD_BILL_TYPE_PURCHASE_RETURN}）、{@code FDate}、{@code FDescription}；</li>
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
 * {@code FDescription='来源单:<ref>'} 查询金蝶是否已有该退货单。不用 LIKE：金蝶 FilterString
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
    /**
     * 分录实体键：**实测是 {@code FPURMRBENTRY}**（无下划线）。
     *
     * <p>2026-11-06 真账套逐个试写：{@code FPUR_MRBENTRY} / {@code FEntity} / {@code FBillEntry}
     * 都会被当成「没给分录」，金蝶随即报一串「第1行字段 XX 是必填项」的误导性错误；
     * 只有 {@code FPURMRBENTRY} 才真正解析分录。别再按「表名 + ENTRY」猜。
     */
    static final String ENTRY_KEY = "FPURMRBENTRY";
    /** 分录必填的货主类型（与入库/出库回写同一口径）。 */
    private static final String OWNER_TYPE_ORG = "BD_OwnerOrg";

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
        // 库存单位必填（2026-11-06 真账套试写报「库存单位是必填项」）。
        // 单位以金蝶物料档案为准（ERP 是主数据唯一来源），不取 WMS 侧冗余字段：
        // 物料档案查不到就显式失败，不发缺单位的报文
        String unit = resolveMaterialUnit(record.getMaterialCode());
        // 分录必填：库存单位 / 计价单位 / 采购单位（三者都传同一库存单位）、货主、供应商、仓库与库存状态。
        // 全部是 2026-11-06 真账套试写出来的必填项，少一个就整单失败。
        entry.put("FUnitID", Map.of("FNumber", unit));
        entry.put("FPRICEUNITID", Map.of("FNumber", unit));
        entry.put("FCarryUnitId", Map.of("FNumber", unit));
        entry.put("FOwnerTypeId", OWNER_TYPE_ORG);
        entry.put("FOWNERID", Map.of("FNumber", properties.getDefaultOrgNumber()));
        entry.put("FStockId", Map.of("FNumber", stockCode));
        if (oConvertUtils.isNotEmpty(properties.getStockStatusId())) {
            entry.put("FStockStatusId", Map.of("FNumber", properties.getStockStatusId()));
        }
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
            // 分录级供应商也是必填（真账套实测），与表头同一口径
            entry.put("FSupplierID", Map.of("FNumber", supplier));
        }
        String orgNumber = properties.getDefaultOrgNumber();
        if (oConvertUtils.isEmpty(orgNumber) || orgNumber.trim().isEmpty()) {
            return WmsKingdeeResult.fail("未配置金蝶默认组织（default-org-number），无法回写" + BILL_NAME);
        }
        model.put("FStockOrgId", Map.of("FNumber", orgNumber.trim()));
        model.put("FStockId", Map.of("FNumber", stockCode));
        // 备注恰好为「来源单:<ref>」——它同时是幂等定位符，不能拼原因等可变内容。
        // 2026-11-06 真账套试写：实体属性是 DESCRIPTION，写 FNote 报「实体不存在此属性」，
        // 查得到 FNote 只是查询层别名，Save 侧必须用 FDescription。
        model.put("FDescription", "来源单:" + reference);
        model.put(ENTRY_KEY, entries);

        log.info("回写金蝶{}({})：不良记录 {}，来源单 {}，受托方 {}，仓库 {}", BILL_NAME, formId,
                record.getId(), reference, supplier, stockCode);
        return formClient.save(formId, model);
    }

    /**
     * 取物料的库存单位编码（{@code BD_Material.FBaseUnitId.FNumber}）。
     *
     * <p>为什么不在 WMS 侧读 {@code wms_material.unit}：单位属于 ERP 主数据，
     * 回写单据的一切字段都以金蝶为准（ERP 是单据信息唯一来源）；查不到直接失败，
     * 而不是把可能过期的本地值写进单据。
     */
    private String resolveMaterialUnit(String materialCode) {
        String filter = "FNumber='" + materialCode.replace("'", "''") + "'";
        WmsKingdeeResult result = formClient.executeBillQuery("BD_Material", "FBaseUnitId.FNumber", filter, 1);
        if (!result.isSuccess()) {
            throw new IllegalStateException("查询物料[" + materialCode + "]库存单位失败："
                    + result.getMessage() + "（无法回写" + BILL_NAME + "）");
        }
        if (result.getRows() == null || result.getRows().isEmpty()
                || result.getRows().get(0).isEmpty()
                || oConvertUtils.isEmpty(String.valueOf(result.getRows().get(0).get(0)))) {
            throw new IllegalStateException("物料[" + materialCode + "]在金蝶没有库存单位，无法回写" + BILL_NAME);
        }
        return String.valueOf(result.getRows().get(0).get(0)).trim();
    }

    @Override
    public boolean existsByReference(String ref) {
        String reference = requireReference(ref);
        String formId = requireFormId();
        // 等值匹配：写入的 FDescription 恰好是「来源单:<ref>」，两边同一口径；
        // LIKE 在金蝶 FilterString 下的可用性与方言转义层数未验证，不采用
        String filter = "FDescription='" + "来源单:" + reference.replace("'", "''") + "'";
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
