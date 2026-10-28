package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;

/**
 * 金蝶仓位属性同步（BD_FLEXVALUES → wms_kingdee_bin）。
 *
 * <p>口径（用户 2026-10-28 明确）：金蝶仓位是**逻辑库位/分类属性**，不是物理货位；
 * 同步只是把这份属性字典拉进 WMS，物理货位按需挂一个属性，两者不是 1:1。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
public interface IWmsKingdeeBinSyncService {

    /**
     * 按组织同步金蝶仓位属性字典。
     *
     * <p>金蝶侧停用/消失的仓位**不物理删除**：若被 WMS 货位引用且该货位有库存，标「待处理」
     * 交人工确认；未被引用则标「金蝶已停用」。
     */
    WmsKingdeeStockSyncResultVO syncBins();
}
