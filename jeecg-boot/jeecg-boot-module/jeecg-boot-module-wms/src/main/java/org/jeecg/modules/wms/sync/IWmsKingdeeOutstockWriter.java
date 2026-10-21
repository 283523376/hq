package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * CK-002 销售出库回写金蝶 SAL_OUTSTOCK。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsKingdeeOutstockWriter {

    WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
