package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;

/**
 * 堆垛机 WCS 网关（真实协议待对方提供，当前为占位契约）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsWcsGateway {

    /**
     * 半成品入库任务下发。
     *
     * @param inbound      生产入库记录
     * @param locationCode 已分配的立库库位编码
     */
    WmsTaskDispatchResult dispatchInbound(WmsProductionInbound inbound, String locationCode);

    /**
     * 任务取消。尽力而为，异常不向上抛。
     */
    void cancelInbound(WmsProductionInbound inbound, String locationCode);

    /**
     * 半成品立库出库任务下发（堆垛机到站取货）。
     */
    WmsTaskDispatchResult dispatchOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation);

    /**
     * 出库任务取消。尽力而为，异常不向上抛。
     */
    void cancelOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation);
}
