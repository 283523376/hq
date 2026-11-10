package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;

/**
 * 生产入库同步网关（ERP/MES/WMS 三地址，报文占位契约）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsProductionInboundSyncGateway {

    /**
     * 同步一条生产入库记录；全部地址未配置时返回模拟成功。
     */
    WmsOrderSyncResult sync(WmsProductionInbound inbound);
}
