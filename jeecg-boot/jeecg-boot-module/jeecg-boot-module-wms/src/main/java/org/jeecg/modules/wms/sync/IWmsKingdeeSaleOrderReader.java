package org.jeecg.modules.wms.sync;

import java.util.Date;

/**
 * @Description: 金蝶销售订单读取（只读；CK-002 方向反转后 WMS 不再回写 SAL_SaleOrder）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsKingdeeSaleOrderReader {

    /** 金蝶是否启用（未启用时返回 mocked 空结果）。 */
    boolean isEnabled();

    /**
     * 按单号查询（含分录）。
     */
    WmsKingdeeSaleOrderQueryResult queryByBillNo(String billNo);

    /**
     * 按日期窗口查询「已审核(C)」订单。
     *
     * @param begin 起始日期（含）
     * @param end   结束日期（含）
     * @param limit 上限
     */
    WmsKingdeeSaleOrderQueryResult queryApproved(Date begin, Date end, int limit);
}
