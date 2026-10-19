package org.jeecg.modules.wms.service;

/**
 * 生产入库设备任务下发/取消（外部调用，必须在事务外执行）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface IWmsProductionTaskService {

    /**
     * 下发任务：设备故障或下发失败时置「异常待人工」并告警。
     * 仅处理 待上架 + (未下发|失败) 的记录，重复调用幂等。
     */
    void dispatchInbound(String inboundId);

    /**
     * 异步下发：提交接口只返回落库结果，设备调用交给后台线程，避免 HTTP 请求阻塞。
     */
    void dispatchInboundAsync(String inboundId);

    /**
     * 关单时尽力取消设备任务，失败只记日志。
     */
    void cancelInbound(String inboundId);
}
