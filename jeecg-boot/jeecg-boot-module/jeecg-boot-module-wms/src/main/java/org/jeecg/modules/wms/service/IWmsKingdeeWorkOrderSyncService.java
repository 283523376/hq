package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;

/**
 * 金蝶生产订单（PRD_MO）→ WMS 生产工单同步。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
public interface IWmsKingdeeWorkOrderSyncService {

    /**
     * 查询 PRD_MO 并按工单号 upsert 到 wms_work_order。
     *
     * @param billNo 指定工单号；为空则按 top 取最近数据
     * @param top    单次查询行数上限
     */
    WmsKingdeeWorkOrderSyncResultVO syncFromKingdee(String billNo, int top);
}
