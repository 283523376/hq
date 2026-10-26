package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsSubcontractSyncResultVO;

/**
 * @Description: 委外订单（SUB_SUBREQORDER）→ 外协出库只读草稿。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsSubcontractOrderSyncService {

    /**
     * 拉取已审核委外订单，按单号 upsert 成只读草稿。
     *
     * <p>本期**不展开 BOM 子项、不拣货、不回写**——委外领料回写的对象是委外 BOM 展开的子项，
     * 而这些子项多为尚未纳入项目范围的原材料。
     *
     * @param top 单次拉取上限
     */
    WmsSubcontractSyncResultVO syncFromKingdee(int top);
}
