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
     * 把处置方式为退货的不良品记录回写为金蝶采购退货单（Save）。
     *
     * @param record 受托不良品记录（{@code disposal_type=RETURN}）
     * @return 金蝶保存结果；成功时 {@code number} 为 ERP 单号
     * @throws IllegalStateException 未配置采购退货单 formId
     */
    WmsKingdeeResult write(WmsDefectRecord record);
}
