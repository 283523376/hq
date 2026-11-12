package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsStocktakeOrder;

/**
 * @Description: 盘点差异回写金蝶的端口（D5 端口-适配器边界）。
 *
 * <p>实现 {@link org.jeecg.modules.wms.service.impl.WmsStocktakeErpWriterImpl}；
 * 端口化后也落入 {@code WmsErpWriteAuditAspect} 的 {@code I*Writer+} 切点覆盖。
 * 此前只有 controller 直注具体类，是全部回写器中唯一没有接口的例外
 * （不变量对照 2026-11-11 §2-3）。
 *
 * <p>盘盈 → 其他入库单（STK_Miscellaneous/QTRK）、盘亏 → 其他出库单（STK_MisDelivery/QTCK），
 * 两侧独立调用独立记日志；单号以金蝶为准，保存后回存 {@code erp_bill_no}。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-11
 */
public interface IWmsStocktakeErpWriter {

    /** 盘盈 → 其他入库单 QTRK。 */
    WmsOrderSyncResult writeGain(WmsStocktakeOrder order);

    /** 盘亏 → 其他出库单 QTCK。 */
    WmsOrderSyncResult writeLoss(WmsStocktakeOrder order);
}
