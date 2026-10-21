package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * CK-002 销售订单回写金蝶 SAL_SaleOrder。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsKingdeeSaleOrderWriter {

    WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
