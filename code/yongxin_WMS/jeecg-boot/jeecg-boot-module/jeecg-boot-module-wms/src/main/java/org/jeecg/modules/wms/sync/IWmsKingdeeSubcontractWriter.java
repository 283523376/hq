package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.util.List;

/**
 * @Description: 委外领料/退料金蝶回写（SUB_PickMtrl / SUB_ReturnMtrl）。
 *
 * <p>本期只有占位实现：委外领料回写的对象是委外 BOM 展开的子项，而子项多为尚未纳入
 * 项目范围的原材料（外部依赖 E6）。接口先立，保持调用方结构稳定。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsKingdeeSubcontractWriter {

    /** 回写委外领料单 SUB_PickMtrl。 */
    WmsOrderSyncResult writePickMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details);

    /** 回写委外退料单 SUB_ReturnMtrl。 */
    WmsOrderSyncResult writeReturnMtrl(WmsOutboundOrder order, List<WmsOutboundDetail> details);
}
