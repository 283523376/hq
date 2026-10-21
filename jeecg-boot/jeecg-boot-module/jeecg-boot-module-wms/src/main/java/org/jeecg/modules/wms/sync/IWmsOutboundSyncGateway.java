package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * 出库同步网关：金蝶优先，未启用时可选 MES HTTP，都未配置则 mock。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsOutboundSyncGateway {

    /** 出库单同步（PRD_PickMtrl / SAL_OUTSTOCK）。 */
    WmsOrderSyncResult sync(WmsOutboundOrder order, List<WmsOutboundDetail> details);

    /** 销售订单同步（SAL_SaleOrder）。 */
    WmsOrderSyncResult syncSaleOrder(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
