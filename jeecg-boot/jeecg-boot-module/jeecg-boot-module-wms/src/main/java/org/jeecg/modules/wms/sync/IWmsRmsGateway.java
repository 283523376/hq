package org.jeecg.modules.wms.sync;

/**
 * 米塔 RMS 网关（RMS 任务接口文档 V1.6）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsRmsGateway {

    /**
     * 入库任务下发：POST /api/v2/task/inbound。
     */
    WmsTaskDispatchResult dispatchInbound(String taskNo, String carrierCode);

    /**
     * 任务取消：POST /api/v2/task/cancel。尽力而为，异常不向上抛。
     */
    void cancelInbound(String taskNo, String carrierCode);
}
