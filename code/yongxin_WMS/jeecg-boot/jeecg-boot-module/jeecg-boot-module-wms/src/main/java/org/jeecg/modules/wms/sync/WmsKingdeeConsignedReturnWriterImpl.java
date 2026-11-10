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
 * @Description: 受托加工材料退料单回写（V2.4 §3.3 受托不良退货）。
 *
 * <p><b>2026-11-06 拍板改投 {@code STK_OEMInStockRETURN}（单据类型 {@code STCLTL01_SYS}）</b>：
 * 原方案写采购退料单 {@code PUR_MRB}，但它强制「供应商」，而受托方是**客户**档案，
 * 真账套直接报「字段"供应商"是必填项」——无论怎么补字段都绕不过去。
 * 受托加工材料退料单天然按「受托方 + 受托入库单」走，不需要供应商。
 *
 * <p><b>字段依据（2026-11-06 真账套逐项试写确定）</b>：
 * <ul>
 *   <li>头：{@code FBillTypeID=STCLTL01_SYS}、{@code FDate}、{@code FStockOrgId}、
 *       {@code FStockId}、{@code FCustId}（受托方客户，<b>必填</b>）、
 *       {@code FNote}（备注，幂等定位符；注意与采购退料单的 {@code FDescription} 不同）；</li>
 *   <li>分录实体键 {@code FBillEntry}；分录必填：{@code FMaterialId}、{@code FQty}、
 *       {@code FUnitID}、{@code FStockId}、{@code FOwnerTypeId=BD_Customer}、{@code FOwnerId}、
 *       {@code FStockStatusId}；</li>
 *   <li>{@code FLot} 批次；{@code FSrcBillNo} 源单编号——<b>自由文本，必须传裸字符串</b>，
 *       传 {@code {FNumber}} 会报「解析字段(Key:FSrcBillNo)时发生异常」；</li>
 *   <li>数量字段就是 {@code FQty}（不是采购退料单的 {@code FRMREALQTY}）。</li>
 * </ul>
 *
 * <p>口径：
 * <ul>
 *   <li><b>不传 {@code FBillNo} / {@code FID}</b>：单号与主键以 ERP 为准；</li>
 *   <li>备注<b>恰好</b>为 {@code 来源单:<ref>}——同时是幂等定位符，不拼原因等可变内容；</li>
 *   <li>受托方由调用方按 {@code record.sourceBillNo} 反查受托入库单的 {@code consignor_id}
 *       得到客户档案后传入——{@code wms_defect_record} 本身不存受托方；</li>
 *   <li>日期取记录创建时间，不取执行瞬间（补跑/重试不会写错日期）；</li>
 *   <li>处置方式必须是 RETURN，否则抛错（防止报废记录误开退料单）；</li>
 *   <li>{@code FSrcBillNo} 仅在来源入库单已有金蝶单号时带（溯源用），没有也能开单。</li>
 * </ul>
 *
 * <p>{@link #isEnabled()} 暴露 {@code wms.sync.erp.kingdee.enabled} 供调用方发起前判断；
 * Writer 不因开关关闭而返回 mock 成功。
 *
 * <p>{@link #existsByReference(String)} 供幂等查重：用<b>等值</b>匹配
 * {@code FNote='来源单:<ref>'} 查询金蝶是否已有该退料单。不用 LIKE：金蝶 FilterString
 * 对 LIKE 的支持与底层方言转义层数均未验证。查询失败抛错而不是返回 false——
 * 把查询失败当成「不存在」会让重试重复开单。
 *
@Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Slf4j
@Service
public class WmsKingdeeConsignedReturnWriterImpl implements IWmsKingdeeConsignedReturnWriter {

    private static final String BILL_NAME = "受托加工材料退料单";
    private static final WmsConfigKey FORM_KEY = WmsConfigKey.KD_FORM_CONSIGNED_RETURN;
    /** 受托方（客户）既是表头 FCustId，也是分录货主（FOwnerTypeId=BD_Customer）。 */
    private static final String OWNER_TYPE_CUSTOMER = "BD_Customer";
    /** 幂等定位符（来源单号）的合法形态：WMS 单号即字母/数字/下划线/连字符。 */
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /**
     * 分录实体键：**受托加工材料退料单实测是 {@code FBillEntry}**（2026-11-06 真账套两次试写确认，
     * 开出 STCLTL000280）。
     *
     * <p>别照抄采购退料单 {@code PUR_MRB} 的 {@code FPURMRBENTRY}：那是另一张单据的分录键，
     * 拿到这里用会被当成「没给分录」，金蝶随即报一串「第1行字段 XX 是必填项」的误导性错误
     * （单位/物料编码/保管者/货主全报一遍），看起来像字段没传，其实是分录键错了。
     */
    static final String ENTRY_KEY = "FBillEntry";
    /** 分录必填的货主类型（与入库/出库回写同一口径）。 */

    private final WmsKingdeeFormClient formClient;
    private final IWmsSystemConfigService configService;
    private final WmsKingdeeProperties properties;

    public WmsKingdeeConsignedReturnWriterImpl(WmsKingdeeFormClient formClient,
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
    public WmsKingdeeResult write(WmsDefectRecord record, String customerNumber,
                                  String warehouseCode, String sourceErpBillNo) {
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
        // 受托方（客户）是表头与分录货主的共同必填项
        String customer = customerNumber == null ? "" : customerNumber.trim();
        if (customer.isEmpty()) {
            return WmsKingdeeResult.fail("受托不良记录[" + record.getId()
                    + "]缺少受托方客户编码，无法回写金蝶" + BILL_NAME);
        }
        // 仓库是金蝶退料单必填：解析不到就显式失败，不发缺 FStockId 的报文
        String stockCode = warehouseCode == null ? "" : warehouseCode.trim();
        if (stockCode.isEmpty()) {
            return WmsKingdeeResult.fail("受托不良记录[" + record.getId()
                    + "]来源入库单无法解析退货仓库编码，无法回写金蝶" + BILL_NAME);
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("FMaterialId", Map.of("FNumber", record.getMaterialCode()));
        entry.put("FQty", qty);
        if (oConvertUtils.isNotEmpty(record.getBatchNo())) {
            entry.put("FLot", Map.of("FNumber", record.getBatchNo()));
        }
        // 以下必填项全部是 2026-11-06 真账套试写确定（STK_OEMInStockRETURN）：
        // 单位取物料档案、货主=受托方客户、仓库与库存状态见配置
        entry.put("FUnitID", Map.of("FNumber", resolveMaterialUnit(record.getMaterialCode())));
        entry.put("FStockId", Map.of("FNumber", stockCode));
        entry.put("FOwnerTypeId", OWNER_TYPE_CUSTOMER);
        entry.put("FOwnerId", Map.of("FNumber", customer));
        if (oConvertUtils.isNotEmpty(properties.getStockStatusId())) {
            entry.put("FStockStatusId", Map.of("FNumber", properties.getStockStatusId()));
        }
        // 源单编号是自由文本（不是基础资料字典，传 {FNumber} 会报解析异常）；
        // 有金蝶受托入库单号就带上，方便在 ERP 里一键联查来源
        if (oConvertUtils.isNotEmpty(sourceErpBillNo)) {
            entry.put("FSrcBillNo", sourceErpBillNo.trim());
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(entry);

        Map<String, Object> model = new LinkedHashMap<>();
        // 业务日期用记录创建时间（不取执行瞬间的 new Date()：补跑/重试会写错日期）
        model.put("FDate", formatDate(record.getCreateTime()));
        model.put("FBillTypeID", Map.of("FNumber", WmsConstants.KD_BILL_TYPE_CONSIGNED_RETURN));
        String orgNumber = properties.getDefaultOrgNumber();
        if (oConvertUtils.isEmpty(orgNumber) || orgNumber.trim().isEmpty()) {
            return WmsKingdeeResult.fail("未配置金蝶默认组织（default-org-number），无法回写" + BILL_NAME);
        }
        model.put("FStockOrgId", Map.of("FNumber", orgNumber.trim()));
        model.put("FStockId", Map.of("FNumber", stockCode));
        // 表头客户必填（真账套报「字段“客户”是必填项」）
        model.put("FCustId", Map.of("FNumber", customer));
        // 备注恰好为「来源单:<ref>」——它同时是幂等定位符，不能拼原因等可变内容。
        // 这张单 Save 侧字段就叫 FNote（与采购退料单的 FDescription 不同，别照抄）
        model.put("FNote", "来源单:" + reference);
        model.put(ENTRY_KEY, entries);

        log.info("回写金蝶{}({})：不良记录 {}，来源单 {}，受托方 {}，仓库 {}", BILL_NAME, formId,
                record.getId(), reference, customer, stockCode);
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
