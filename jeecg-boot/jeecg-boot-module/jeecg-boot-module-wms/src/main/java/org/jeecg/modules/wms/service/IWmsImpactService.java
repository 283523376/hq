package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsImpactVO;

/**
 * @Description: 影响面分析（XT-002）——按单号反查这张单在 WMS 影响了什么。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
public interface IWmsImpactService {

    /**
     * 按工单号汇总影响面：工单本身、入库单、出库/领料单及其明细、
     * 由「物料+批次」反查的当前库存行、回写过的 ERP 单号、审计时间线。
     *
     * @param workOrderNo 工单号，不能为空
     * @return 影响面；查不到的部分写进 {@code gaps}，不抛异常
     * @throws RuntimeException 单号为空
     */
    WmsImpactVO analyzeByWorkOrderNo(String workOrderNo);
}
