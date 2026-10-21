package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * CK-001 生产领料回写金蝶 PRD_PickMtrl。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsKingdeePickWriter {

    WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
