package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOrder;

/**
 * @Description: 订单同步至 ERP/MES 的网关（SPI）。
 *
 * <p>本接口只负责「怎么和 ERP/MES 说一次话」，<b>不关心</b>重试、退避、告警与落库；
 * 那些由 {@code IWmsOrderSyncService} 编排。接入真实 ERP/MES 时只需替换本接口的实现类。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
public interface WmsOrderSyncGateway {

    /**
     * 向 ERP/MES 提交一次订单同步。
     *
     * <p><b>约定：本方法不得抛异常</b>。任何网络/协议/业务失败都必须转成
     * {@link WmsOrderSyncResult#fail(String)} 返回，让上层的重试编排统一处理。
     *
     * @param order 待同步订单
     * @return 本次尝试的结果
     */
    WmsOrderSyncResult sync(WmsOrder order);
}
