package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;

/**
 * @Description: 订单同步重试编排。
 *
 * <p>职责边界：<b>只管</b>重试次数、退避、告警与日志；<b>不管</b> HTTP 细节（交给
 * {@code WmsOrderSyncGateway}），<b>也不写库</b>（交给调用方，保证「谁拥有数据谁写」）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface IWmsOrderSyncService {

    /**
     * 同步订单至 ERP/MES，失败自动重试至多 {@code WmsConstants.SYNC_MAX_RETRY} 次。
     *
     * @param order    待同步订单
     * @param announce 失败时是否投递站内消息告警。用户触发路径传 true；
     *                 定时补偿路径传 false（防每轮轰炸）
     * @return 最后一次尝试的结果与本次实际重试次数
     */
    WmsOrderSyncAttempt syncOrder(WmsOrder order, boolean announce);
}
