package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;

/**
 * 生产入库回写金蝶 PRD_INSTOCK（Save + Submit）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
public interface IWmsKingdeeInboundWriter {

    /**
     * 幂等回写：FBillNo = WMS 入库单号；已审核直接成功，草稿/审核中提交。
     */
    WmsOrderSyncResult write(WmsProductionInbound inbound);
}
