package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsDefectRecord;

/**
 * 采购退货单回写（V2.4 §3.3 受托不良退货）。
 *
 * <p>金蝶 formId 由配置项 {@code kingdee.form.purchase-return} 提供，未配置即显式失败。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeePurchaseReturnWriter {

    /**
     * 金蝶同步总开关（{@code wms.sync.erp.kingdee.enabled}）。
     *
     * <p>给调用方判断是否发起回写；Writer 不因开关关闭而返回 mock 成功。
     */
    boolean isEnabled();

    /**
     * 把处置方式为退货的不良品记录回写为金蝶采购退货单（Save）。
     *
     * @param record         受托不良品记录（{@code disposal_type=RETURN}）
     * @param supplierNumber 受托方（供应商）编码：调用方按 {@code record.sourceBillNo}
     *                       反查入库单得到后传入——不良品记录本身不存受托方
     * @return 金蝶保存结果；成功时 {@code number} 为 ERP 单号
     * @throws IllegalStateException 未配置采购退货单 formId
     */
    WmsKingdeeResult write(WmsDefectRecord record, String supplierNumber);

    /**
     * 幂等查重：按备注中的参考号（通常为来源单号）查这张退货单是否已经开过。
     *
     * <p>过滤条件是<b>包含匹配</b> {@code FNote like '%来源单:<ref>%'}，与 {@link #write}
     * 写入的备注（「WMS受托不良退货；来源单:…；原因:…」）保持自洽——用等值查询会永远
     * 查不到自己写的单，幂等保护等于没接。{@code ref} 中的 {@code '}、{@code %}、
     * {@code _}、{@code \} 全部转义。
     *
     * <p>调用方在写单前先查，查到即视为已回写，避免重试重复开单。
     *
     * @param ref 参考号（不得为空、不得含控制字符）
     * @return 金蝶已有该参考号的退货单返回 true
     * @throws IllegalStateException 未配置采购退货单 formId，或查询失败（查重失败不能当成「不存在」）
     */
    boolean existsByReference(String ref);
}
