package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;

/**
 * 金蝶生产订单（PRD_MO）→ WMS 生产工单同步（BD-002 V2.2：ERP 为唯一业务来源）。
 *
 * <p>状态门槛：金蝶 C(已审核)→已下达、A/B(未审核)→待生产、D 及未知整单跳过并告警。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
public interface IWmsKingdeeWorkOrderSyncService {

    /**
     * 查询 PRD_MO 并按工单号 upsert 到 wms_work_order。
     *
     * @param billNo    指定工单号；为空则按窗口取数
     * @param startDate 窗口起（yyyy-MM-dd，含）；与 endDate 均为空时默认最近 7 天
     * @param endDate   窗口止（yyyy-MM-dd，含）
     * @param top       单次查询行数上限；空则 50，最大 200
     * @return 结果（含实际生效窗口，供前端提示，避免误以为全量）
     */
    WmsKingdeeWorkOrderSyncResultVO syncFromKingdee(String billNo, String startDate, String endDate, Integer top);

    /**
     * 按单号同步一个工单。
     *
     * @return true=有新增或更新；false=金蝶无此单或被跳过
     */
    boolean syncSingleFromKingdee(String billNo);

    /**
     * 扫码/按单号查询工单：先查本地，查不到时按单号从 ERP 同步一次再查（BD-002 V2.2 回源）。
     * 同步仍查不到时抛出原始「工单号无效」。
     */
    WmsWorkOrderInfoVO scanWithErpFallback(String workOrderNo);
}
