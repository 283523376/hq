package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * 出库分配器：FIFO 选库存行、锁定数量、生成分配明细。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
public interface IWmsOutboundAllocator {

    /**
     * 按需求明细分配库存并锁定；库存不足或并发抢占时抛异常，由调用方回滚整单。
     */
    void allocate(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
