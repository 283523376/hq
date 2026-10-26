package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Description: 样品出库金蝶回写——本期**只做明确报错**，不发请求。
 *
 * <p>两道关卡：
 * <ol>
 *   <li>{@code wms.outbound.sample.stock-code} 未配置 → 报「未配置」；</li>
 *   <li>即使配置了，单据形态（调拨单/其他出库单）仍待客户确认 → 报「形态待确认」。</li>
 * </ol>
 * 绝不在形态未定时猜一种单据写进 ERP——写错方向会让库存凭空增减。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
public class WmsSampleOutboundErpWriterImpl implements IWmsSampleOutboundErpWriter {

    private final WmsKingdeeProperties properties;
    /** 形态确认后在此调用 save/submit；本期刻意不调用。 */
    private final WmsKingdeeFormClient formClient;

    public WmsSampleOutboundErpWriterImpl(WmsKingdeeProperties properties,
                                          WmsKingdeeFormClient formClient) {
        this.properties = properties;
        this.formClient = formClient;
    }

    @Override
    public WmsOrderSyncResult write(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        String stockCode = properties.getSample().getStockCode();
        if (!StringUtils.hasText(stockCode)) {
            return WmsOrderSyncResult.fail("样品回写未配置 wms.sync.erp.kingdee.sample.stock-code（外部依赖 E5）");
        }
        log.warn("样品出库回写被调用但单据形态未确认，单号={}", order == null ? null : order.getOutboundNo());
        return WmsOrderSyncResult.fail("样品回写单据形态待客户确认（外部依赖 E5）");
    }
}
