package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;

/**
 * 纯不良品 → 金蝶其他入库单（STK_Miscellaneous）回写器。
 *
 * <p>PRD_INSTOCK 没有不良数量字段，纯不良品（良品=0）无法走生产入库回写；
 * 按「自动化优先」原则自动开其他入库单把不良品记入金蝶账，代替原先的「请人工处理」。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
public interface IWmsKingdeeDefectWriter {

    /**
     * 把纯不良品入库记录回写为金蝶其他入库单（保存+提交，审核走金蝶工作流）。
     *
     * @param inbound 纯不良品入库记录（{@code goodQty<=0} 且 {@code badQty>0}）
     * @return 同步结果；成功时金蝶单号已回存 {@code wms_production_inbound.erp_bill_no}
     */
    WmsOrderSyncResult write(WmsProductionInbound inbound);
}
