package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;

import java.util.List;

/**
 * 入库单 ERP 同步网关（HTTP 细节隔离在此，重试编排不关心传输层）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInboundErpSyncGateway {

    /**
     * 提交入库单（含明细）至 ERP。
     *
     * @param order   入库单
     * @param details 明细
     * @return 同步结果；约定不抛异常，失败也转成结果对象
     */
    WmsOrderSyncResult sync(WmsInboundOrder order, List<WmsInboundDetail> details);
}
