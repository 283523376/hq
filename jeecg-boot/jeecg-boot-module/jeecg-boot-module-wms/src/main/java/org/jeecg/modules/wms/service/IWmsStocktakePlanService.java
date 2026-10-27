package org.jeecg.modules.wms.service;

/**
 * @Description: 盘点计划执行（定期 / 周期滚动）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsStocktakePlanService {

    /** 扫描启用中的计划并到期的执行；周期计划按滚动游标圈定库位。 */
    void runDuePlans();
}
