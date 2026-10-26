package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * @Description: 样品出库金蝶回写适配器。
 *
 * <p>单据形态（调拨单 `STK_TransferDirect` 或其他出库单 `STK_MisDelivery`）待客户确认
 * （外部依赖 E5）；本接口先立，接入时只换实现。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsSampleOutboundErpWriter {

    WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
