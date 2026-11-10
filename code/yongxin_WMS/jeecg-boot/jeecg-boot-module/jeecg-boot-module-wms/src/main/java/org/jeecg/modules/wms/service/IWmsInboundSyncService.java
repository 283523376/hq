package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

/**
 * 入库单 ERP 同步重试编排（实现随 Task 7 落地；putaway 内联调用）。
 *
 * <p>职责边界与 {@link IWmsOrderSyncService} 一致：只管重试次数、退避、告警与日志；
 * 不管 HTTP 细节（交给 WmsInboundErpSyncGateway），也不写库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsInboundSyncService {

    /**
     * 同步入库单至 ERP，失败自动重试至多 {@code WmsConstants.SYNC_MAX_RETRY} 次。
     *
     * @param order    待同步入库单
     * @param announce 失败时是否投递站内消息告警
     * @return 最后一次尝试的结果与本次实际重试次数
     */
    WmsOrderSyncAttempt syncInbound(WmsInboundOrder order, boolean announce);
}
