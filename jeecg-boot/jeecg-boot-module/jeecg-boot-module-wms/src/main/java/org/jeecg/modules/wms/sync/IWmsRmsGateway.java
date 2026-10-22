package org.jeecg.modules.wms.sync;

import java.util.List;

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
     * 工作站强制入库：POST /api/v2/task/inbound/force。
     * 工作站内的容器锁定并呼叫机器人前往对接；300012（已在呼叫）按幂等成功处理。
     */
    WmsTaskDispatchResult forceInbound(String taskNo, String carrierCode);

    /**
     * 任务取消：POST /api/v2/task/cancel。尽力而为，异常不向上抛。
     */
    void cancelInbound(String taskNo, String carrierCode);

    /**
     * 出库任务下发：POST /api/v2/task/outbound（批量，单条失败即整体失败）。
     * 300001（任务已存在）按幂等成功处理；300000 退避重试。
     */
    WmsTaskDispatchResult dispatchOutbound(List<WmsRmsOutboundTask> tasks);

    /**
     * 出库任务取消：POST /api/v2/task/cancel。尽力而为，异常不向上抛。
     */
    void cancelOutbound(String taskNo, String containerCode);

    /**
     * 搬运（移库）任务下发：POST /api/v2/task/transfer。
     *
     * <p>米塔只能指定目标<b>区域</b>（{@code zoneCode}），不能指定库位；跨仓段不经过米塔。
     * 300001（任务号已存在）按幂等成功处理；300000 退避重试；300007/300013 原样透出转人工。
     */
    WmsTaskDispatchResult dispatchTransfer(String zoneCode, String warehouse, List<WmsRmsTransferTask> tasks);

    /**
     * 搬运任务取消：POST /api/v2/task/cancel（type=TRANSFER）。尽力而为，异常不向上抛。
     */
    void cancelTransfer(String taskNo, String containerCode);
}
