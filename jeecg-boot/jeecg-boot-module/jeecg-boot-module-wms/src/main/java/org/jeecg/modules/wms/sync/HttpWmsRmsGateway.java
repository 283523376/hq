package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 米塔 RMS 入库任务网关（RMS任务接口文档 V1.6）。
 *
 * <p>配置 {@code wms.sync.rms.url}；未配置时模拟受理，保证无设备环境可闭环演示。
 * 错误码 300000（可重试）在网关内退避重试，其余错误码原样返回给业务层分类处理。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class HttpWmsRmsGateway implements IWmsRmsGateway {

    private final String baseUrl;
    private final String stationCode;
    private final String warehouse;
    private final int priority;
    private final RestTemplate restTemplate;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    public HttpWmsRmsGateway(@Value("${wms.sync.rms.url:}") String baseUrl,
                             @Value("${wms.sync.rms.station-code:}") String stationCode,
                             @Value("${wms.sync.rms.warehouse:MITA}") String warehouse,
                             @Value("${wms.sync.rms.priority:1}") int priority,
                             @Value("${wms.sync.rms.timeout-ms:60000}") int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.stationCode = stationCode == null ? "" : stationCode.trim();
        this.warehouse = warehouse == null || warehouse.trim().isEmpty() ? "MITA" : warehouse.trim();
        this.priority = priority;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsTaskDispatchResult dispatchInbound(String taskNo, String carrierCode) {
        if (baseUrl.isEmpty()) {
            log.info("未配置 RMS 地址（wms.sync.rms.url），任务[{}]按模拟受理处理", taskNo);
            return WmsTaskDispatchResult.okMock();
        }
        WmsTaskDispatchResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            last = callInbound(taskNo, carrierCode);
            if (last.isSuccess() || !last.isRetryable() || i == WmsConstants.SYNC_MAX_RETRY) {
                return last;
            }
            sleepBeforeRetry(i, taskNo);
        }
        return last;
    }

    private WmsTaskDispatchResult callInbound(String taskNo, String carrierCode) {
        try {
            Map<String, Object> container = new LinkedHashMap<>();
            container.put("containerCode", carrierCode);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("warehouse", warehouse);
            payload.put("taskNo", taskNo);
            payload.put("stationCode", stationCode);
            payload.put("container", container);
            payload.put("priority", priority);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    join(baseUrl, "/api/v2/task/inbound"), new HttpEntity<>(payload, headers), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return WmsTaskDispatchResult.fail(null,
                        "RMS 返回非成功状态：" + response.getStatusCode(), true);
            }
            Map<String, Object> body = response.getBody();
            int code = toInt(body.get("code"), -1);
            String msg = body.get("msg") == null ? "" : String.valueOf(body.get("msg"));
            if (code == 0) {
                return WmsTaskDispatchResult.ok();
            }
            return WmsTaskDispatchResult.fail(code, msg, code == WmsConstants.RMS_ERROR_RETRYABLE);
        } catch (Exception e) {
            log.warn("任务[{}]下发 RMS 异常：{}", taskNo, e.getMessage());
            return WmsTaskDispatchResult.fail(null, "调用 RMS 异常：" + e.getMessage(), true);
        }
    }

    @Override
    public void cancelInbound(String taskNo, String carrierCode) {
        if (baseUrl.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("containerCode", carrierCode);
            detail.put("taskNo", taskNo);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "INBOUND");
            payload.put("details", List.of(detail));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(join(baseUrl, "/api/v2/task/cancel"),
                    new HttpEntity<>(payload, headers), Map.class);
        } catch (Exception e) {
            log.warn("任务[{}]取消 RMS 任务失败（不阻塞关单）：{}", taskNo, e.getMessage());
        }
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

    private String join(String base, String path) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
