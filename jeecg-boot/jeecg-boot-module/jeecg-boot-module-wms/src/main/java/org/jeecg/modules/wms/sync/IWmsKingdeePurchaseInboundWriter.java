package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsInboundOrder;

/**
 * 采购入库单回写（V2.4 §3.2 段差棒直入）。
 *
 * <p>金蝶 formId 由配置项 {@code kingdee.form.purchase-inbound} 提供，未配置即显式失败。
 *
 * @author 永鑫WMS
 * @since 2026-11-04
 */
public interface IWmsKingdeePurchaseInboundWriter {

    /**
     * 把段差棒直入的 WMS 采购入库单回写为金蝶采购入库单（Save）。
     *
     * @param order WMS 入库单（{@code source_type=PURCHASE}）
     * @return 金蝶保存结果；成功时 {@code number} 为 ERP 单号
     * @throws IllegalStateException 未配置采购入库单 formId
     */
    WmsKingdeeResult write(WmsInboundOrder order);
}
