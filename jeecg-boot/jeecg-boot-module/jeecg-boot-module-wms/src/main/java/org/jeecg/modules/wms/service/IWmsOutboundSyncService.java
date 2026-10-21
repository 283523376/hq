package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

import java.util.Date;
import java.util.List;

/**
 * 出库同步重试编排（出库单 + 销售订单）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsOutboundSyncService {

    WmsOrderSyncAttempt sync(String outboundId, boolean announce);

    WmsOrderSyncAttempt syncSaleOrder(String outboundId, boolean announce);

    void persistResult(String id, WmsOrderSyncAttempt attempt);

    void persistSaleResult(String id, WmsOrderSyncAttempt attempt);

    /**
     * 补偿任务用：连续失败恰好达到阈值时补发一次告警。
     *
     * @param saleOrder true = 销售订单同步，false = 出库单同步
     * @return 是否发送了告警
     */
    boolean announceFailureIfNeeded(String outboundId, boolean saleOrder);

    Date syncStaleBefore();

    int claimForSync(String id, Date staleBefore);

    int claimSaleForSync(String id, Date staleBefore);

    List<WmsOutboundOrder> selectSyncCandidates(Date staleBefore, int limit);

    List<WmsOutboundOrder> selectSaleSyncCandidates(Date staleBefore, int limit);

    List<WmsOutboundOrder> selectUndispatchedBefore(Date before, int limit);
}
