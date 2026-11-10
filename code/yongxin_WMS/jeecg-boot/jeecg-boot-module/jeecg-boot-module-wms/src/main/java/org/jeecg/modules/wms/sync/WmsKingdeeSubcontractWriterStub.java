package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Description: 委外回写占位实现——**一律明确失败**，绝不返回成功。
 *
 * <p>为什么必须失败而不是静默成功：调用方若把占位实现当成功，会把「没写进 ERP」当成
 * 「已写进 ERP」，账实从此不一致且无告警。失败至少会让调用方记录并暴露问题。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
public class WmsKingdeeSubcontractWriterStub implements IWmsKingdeeSubcontractWriter {

    private static final String NOT_IMPLEMENTED =
            "委外回写未实现：待原材料纳入项目范围（外部依赖 E6）";

    @Override
    public WmsOrderSyncResult writePickMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        log.warn("委外领料回写被调用但尚未实现，单号={}", order == null ? null : order.getOutboundNo());
        return WmsOrderSyncResult.fail(NOT_IMPLEMENTED);
    }

    @Override
    public WmsOrderSyncResult writeReturnMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        log.warn("委外退料回写被调用但尚未实现，单号={}", order == null ? null : order.getOutboundNo());
        return WmsOrderSyncResult.fail(NOT_IMPLEMENTED);
    }
}
