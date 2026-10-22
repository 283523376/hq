package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Description: 米塔 RMS 入库任务网关（RMS任务接口文档 V1.6）。
 *
 * <p>协议与超时由 {@link WmsRmsClient} 负责（默认 30s）；本类负责：
 * <ul>
 *   <li>未配置地址时模拟受理（无设备环境可闭环演示）；</li>
 *   <li>300000 退避重试；300001（入库）/300012（强制入库）按幂等成功处理；</li>
 *   <li>其余错误码带中文描述返回，由调用方转人工。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class HttpWmsRmsGateway implements IWmsRmsGateway {

    private final WmsRmsClient client;
    private final String stationCode;
    private final String warehouse;
    private final int priority;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    public HttpWmsRmsGateway(WmsRmsClient client,
                             @Value("${wms.sync.rms.station-code:}") String stationCode,
                             @Value("${wms.sync.rms.warehouse:MITA}") String warehouse,
                             @Value("${wms.sync.rms.priority:1}") int priority) {
        this.client = client;
        this.stationCode = stationCode == null ? "" : stationCode.trim();
        this.warehouse = warehouse == null || warehouse.trim().isEmpty() ? "MITA" : warehouse.trim();
        this.priority = priority;
    }

    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsTaskDispatchResult dispatchInbound(String taskNo, String carrierCode) {
        if (!client.isConfigured()) {
            log.info("未配置 RMS 地址（wms.sync.rms.url），任务[{}]按模拟受理处理", taskNo);
            return WmsTaskDispatchResult.okMock();
        }
        WmsTaskDispatchResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            WmsRmsResponse response = client.inbound(taskNo, carrierCode, stationCode, warehouse, priority);
            last = toResult(response, WmsRmsErrorCode::isInboundIdempotentSuccess);
            if (last.isSuccess() || !last.isRetryable() || i == WmsConstants.SYNC_MAX_RETRY) {
                return last;
            }
            sleepBeforeRetry(i, taskNo);
        }
        return last;
    }

    @Override
    public WmsTaskDispatchResult forceInbound(String taskNo, String carrierCode) {
        if (!client.isConfigured()) {
            log.info("未配置 RMS 地址（wms.sync.rms.url），任务[{}]强制入库按模拟受理处理", taskNo);
            return WmsTaskDispatchResult.okMock();
        }
        WmsRmsResponse response = client.inboundForce(stationCode, taskNo, List.of(carrierCode));
        return toResult(response, WmsRmsErrorCode::isForceIdempotentSuccess);
    }

    @Override
    public WmsTaskDispatchResult dispatchOutbound(List<WmsRmsOutboundTask> tasks) {
        if (!client.isConfigured()) {
            log.info("未配置 RMS 地址（wms.sync.rms.url），出库任务按模拟受理处理");
            return WmsTaskDispatchResult.okMock();
        }
        WmsTaskDispatchResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            WmsRmsResponse response = client.outbound(null, tasks);
            last = toResult(response, WmsRmsErrorCode::isOutboundIdempotentSuccess);
            if (last.isSuccess() || !last.isRetryable() || i == WmsConstants.SYNC_MAX_RETRY) {
                return last;
            }
            sleepBeforeRetry(i, tasks.isEmpty() ? "" : tasks.get(0).getTaskNo());
        }
        return last;
    }

    @Override
    public void cancelOutbound(String taskNo, String containerCode) {
        if (!client.isConfigured()) {
            return;
        }
        try {
            WmsRmsResponse response = client.cancel(taskNo, containerCode);
            if (!response.isSuccess()) {
                log.warn("任务[{}]取消 RMS 出库任务失败：{}", taskNo, response.getMessage());
            }
        } catch (Exception e) {
            log.warn("任务[{}]取消 RMS 出库任务异常（不阻塞关单）：{}", taskNo, e.getMessage());
        }
    }

    @Override
    public void cancelInbound(String taskNo, String carrierCode) {
        if (!client.isConfigured()) {
            return;
        }
        try {
            WmsRmsResponse response = client.cancel(taskNo, carrierCode);
            if (!response.isSuccess()) {
                log.warn("任务[{}]取消 RMS 任务失败：{}", taskNo, response.getMessage());
            }
        } catch (Exception e) {
            log.warn("任务[{}]取消 RMS 任务异常（不阻塞关单）：{}", taskNo, e.getMessage());
        }
    }

    @Override
    public WmsTaskDispatchResult dispatchTransfer(String zoneCode, String warehouse, List<WmsRmsTransferTask> tasks) {
        if (!client.isConfigured()) {
            log.info("未配置 RMS 地址（wms.sync.rms.url），搬运任务按模拟受理处理");
            return WmsTaskDispatchResult.okMock();
        }
        WmsTaskDispatchResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            WmsRmsResponse response = client.transfer(zoneCode, warehouse, priority, tasks);
            last = toResult(response, WmsRmsErrorCode::isTransferIdempotentSuccess);
            if (last.isSuccess() || !last.isRetryable() || i == WmsConstants.SYNC_MAX_RETRY) {
                return last;
            }
            sleepBeforeRetry(i, tasks.isEmpty() ? "" : tasks.get(0).getTaskNo());
        }
        return last;
    }

    @Override
    public void cancelTransfer(String taskNo, String containerCode) {
        if (!client.isConfigured()) {
            return;
        }
        try {
            WmsRmsResponse response = client.cancel("TRANSFER", taskNo, containerCode);
            if (!response.isSuccess()) {
                log.warn("任务[{}]取消 RMS 搬运任务失败：{}", taskNo, response.getMessage());
            }
        } catch (Exception e) {
            log.warn("任务[{}]取消 RMS 搬运任务异常（不阻塞关单）：{}", taskNo, e.getMessage());
        }
    }

    /** RMS 响应 → 业务结果：区分幂等成功、可重试失败与需转人工的业务错误。 */
    private WmsTaskDispatchResult toResult(WmsRmsResponse response,
                                           java.util.function.IntPredicate idempotentSuccess) {
        if (response.isTransportError()) {
            return WmsTaskDispatchResult.fail(null, response.getMessage(), true);
        }
        int httpStatus = response.getHttpStatus();
        if (httpStatus < 200 || httpStatus >= 300) {
            return WmsTaskDispatchResult.fail(null, response.getMessage(), httpStatus >= 500);
        }
        int code = response.getCode();
        if (idempotentSuccess.test(code)) {
            return WmsTaskDispatchResult.ok();
        }
        String description = WmsRmsErrorCode.describe(code);
        String base = response.getMessage() == null ? "" : response.getMessage();
        String message = base.contains(description)
                ? base
                : base + "（" + code + " " + description + "）";
        return WmsTaskDispatchResult.fail(code, message, WmsRmsErrorCode.isRetryable(code));
    }

    private void sleepBeforeRetry(int index, String taskNo) {
        long wait = index < backoffMs.length ? backoffMs[index] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("任务[{}]RMS 重试等待被中断", taskNo);
        }
    }
}
