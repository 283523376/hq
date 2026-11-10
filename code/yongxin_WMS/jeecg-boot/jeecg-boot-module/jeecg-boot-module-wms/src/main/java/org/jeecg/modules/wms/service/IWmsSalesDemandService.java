package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsDemandResultVO;

/**
 * 销售出库待办自动生成（金蝶发货通知单 → WMS 出库单，2026-10-28 自动化优先 P1）。
 *
 * <p>口径（用户 2026-10-28 确认）：
 * <ul>
 *   <li>只取**已审核（C）**的发货通知单，且分录未出库数量 {@code FRemainOutQty > 0}</li>
 *   <li>同一行**允许多次生成**待办（分批发货）：本次数量 = 未出库数量 − WMS 在途已申请量</li>
 *   <li>金蝶出货仓库 → WMS 库区的映射是预留接口，未命中即跳过该行（仓库主数据尚未建立）</li>
 * </ul>
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
public interface IWmsSalesDemandService {

    /** 扫描金蝶发货通知单并生成 WMS 销售出库待办。 */
    WmsDemandResultVO generatePendingSalesOutbounds();
}
