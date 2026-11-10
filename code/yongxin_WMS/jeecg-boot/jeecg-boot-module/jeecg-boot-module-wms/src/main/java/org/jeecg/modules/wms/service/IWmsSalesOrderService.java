package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsSalesOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrderDetail;
import org.jeecg.modules.wms.vo.WmsSalesOrderVO;

import java.util.Date;
import java.util.List;

/**
 * @Description: 销售订单快照服务（CK-002 方向反转：ERP → WMS）。
 *
 * <p>WMS 只读金蝶 `SAL_SaleOrder`；出库单必须来自快照分录，数量不得超过 `order_qty - shipped_qty`。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsSalesOrderService extends JeecgService<WmsSalesOrder> {

    /** 按单号拉取（实时查金蝶并 upsert 快照）。 */
    WmsSalesOrder syncByBillNo(String billNo);

    /**
     * 按日期窗口拉取「已审核(C)」订单。
     *
     * @return 拉取成功的单数
     */
    int syncByWindow(Date begin, Date end);

    /** 预览：先查快照，未命中则实时拉一次；返回分录与剩余可出数量。 */
    WmsSalesOrderVO preview(String billNo);

    /** 校验订单可出库（必须是 C，且存在快照）；返回快照明细。 */
    List<WmsSalesOrderDetail> requireApprovedDetails(String billNo);

    /** 刷新指定订单的已出库数量（确认出库后调用）。 */
    void refreshShippedQty(String billNo);
}
