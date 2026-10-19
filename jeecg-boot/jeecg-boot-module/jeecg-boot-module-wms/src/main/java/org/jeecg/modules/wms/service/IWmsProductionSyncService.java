package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

/**
 * 生产入库三方同步编排（不写库，结果由调用方 persistSyncResult 落库）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsProductionSyncService {

    /**
     * 同步一条生产入库记录，失败按常量退避重试并在最终失败时告警。
     */
    WmsOrderSyncAttempt syncInbound(String inboundId, boolean announce);

    /**
     * 后台补偿累计失败达到阈值时发一次告警（避免每轮静默重试）。
     */
    void alertRepeatedFailure(WmsProductionInbound record, String error);
}
