package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsDefectRecord;

/**
 * 受托加工材料退料单回写（V2.4 §3.3 受托不良退货）。
 *
 * <p>金蝶 formId 由配置项 {@code kingdee.form.consigned-return} 提供（默认对象
 * {@code STK_OEMInStockRETURN}），未配置即显式失败。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeeConsignedReturnWriter {

    /**
     * 金蝶同步总开关（{@code wms.sync.erp.kingdee.enabled}）。
     *
     * <p>给调用方判断是否发起回写；Writer 不因开关关闭而返回 mock 成功。
     */
    boolean isEnabled();

    /**
     * 把处置方式为退货的不良品记录回写为金蝶受托加工材料退料单（Save）。
     *
     * @param record         受托不良品记录（{@code disposal_type=RETURN}）
     * @param customerNumber 受托方（客户）编码：调用方按 {@code record.sourceBillNo}
     *                       反查入库单的 {@code consignor_id} 得到客户档案后传入
     *                       ——不良品记录本身不存受托方
     * @param warehouseCode  退货仓库编码（{@code FStockId.FNumber}）：由调用方按来源入库单
     *                       明细的货位反查 WMS 仓库得到（编码与金蝶 1:1）；必填
     * @param sourceErpBillNo 来源受托入库单的金蝶单号（{@code FSrcBillNo}，溯源用）；
     *                       可为空——入库单尚未回写成功时没有单号，不影响开单
     * @return 金蝶保存结果；成功时 {@code number} 为 ERP 单号
     * @throws IllegalStateException 未配置受托加工材料退料单 formId
     */
    WmsKingdeeResult write(WmsDefectRecord record, String customerNumber, String warehouseCode,
                           String sourceErpBillNo);

    /**
     * 幂等查重：按来源单号查这张退货单是否已经开过。
     *
     * <p>写入的备注恰好是 {@code 来源单:<ref>}，这里用<b>等值</b>匹配
     * {@code FNote='来源单:<ref>'}——两边同一口径，不依赖 LIKE（金蝶 FilterString 的
     * LIKE 支持与底层方言转义层数未验证）。{@code ref} 与 {@link #write} 共用同一套
     * trim + 字符集校验（{@code ^[A-Za-z0-9_-]{1,64}$}）。
     *
     * <p>调用方在写单前先查，查到即视为已回写，避免重试重复开单。
     *
     * @param ref 参考号（来源单号，trim 后须匹配 {@code ^[A-Za-z0-9_-]{1,64}$}）
     * @return 金蝶已有该参考号的退货单返回 true
     * @throws IllegalArgumentException 参考号为空或不合法
     * @throws IllegalStateException    未配置受托加工材料退料单 formId，或查询失败（查重失败不能当成「不存在」）
     */
    boolean existsByReference(String ref);
}
