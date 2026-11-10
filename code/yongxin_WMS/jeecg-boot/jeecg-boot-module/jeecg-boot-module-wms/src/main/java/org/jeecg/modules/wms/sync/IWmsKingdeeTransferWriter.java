package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;

import java.util.List;

/**
 * @Description: 库存调拨 → 金蝶直接调拨单（STK_TransferDirect）回写。
 *
 * <p>口径（spec §7.2）：
 * <ul>
 *   <li>只做 <b>Save + Submit</b>，<b>绝不调用 Audit</b>（单据类型关联工作流，WebAPI 审批会被拒）；</li>
 *   <li>Submit 后停在 B（审核中），C（已审核）由金蝶工作流审批产生，WMS 只轮询；</li>
 *   <li>仓库级调拨：<b>不传</b> 仓位（`FSrcStockLocId/FDestStockLocId`），101 实测可 Save/Submit。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsKingdeeTransferWriter {

    /** 金蝶业务同步是否启用（未启用时各方法返回 mocked 结果）。 */
    boolean isEnabled();

    /**
     * 保存并提交调拨单（幂等键 FBillNo = WMS 调拨单号）。
     *
     * @return 结果；成功时 mocked=true 表示「金蝶未启用，按模拟已提交处理」
     */
    WmsOrderSyncResult saveAndSubmit(WmsTransferOrder order, List<WmsTransferDetail> details);

    /**
     * 查询单据状态。
     *
     * @param billNo 金蝶单号（= WMS 调拨单号）
     */
    WmsKingdeeTransferStatus queryStatus(String billNo);
}
