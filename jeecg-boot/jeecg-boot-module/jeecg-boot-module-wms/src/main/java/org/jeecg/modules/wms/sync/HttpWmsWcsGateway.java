package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Description: 堆垛机 WCS 网关（占位契约，真实协议待对方提供）。
 *
 * <p>配置 {@code wms.sync.wcs.inbound-url}；未配置时模拟受理。
 * 真实协议确认后只改本适配层，不改业务层。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class HttpWmsWcsGateway implements IWmsWcsGateway {

    private static final int TIMEOUT_MS = 60000;

    private final String inboundUrl;
    private final String cancelUrl;
    private final String outboundUrl;
    private final String cancelOutboundUrl;
    private final String username;
    private final String password;
    private final RestTemplate restTemplate;

    public HttpWmsWcsGateway(@Value("${wms.sync.wcs.inbound-url:}") String inboundUrl,
                             @Value("${wms.sync.wcs.cancel-url:}") String cancelUrl,
                             @Value("${wms.sync.wcs.outbound-url:}") String outboundUrl,
                             @Value("${wms.sync.wcs.cancel-outbound-url:}") String cancelOutboundUrl,
                             @Value("${wms.sync.wcs.username:}") String username,
                             @Value("${wms.sync.wcs.password:}") String password) {
        this.inboundUrl = inboundUrl == null ? "" : inboundUrl.trim();
        this.cancelUrl = cancelUrl == null ? "" : cancelUrl.trim();
        this.outboundUrl = outboundUrl == null ? "" : outboundUrl.trim();
        this.cancelOutboundUrl = cancelOutboundUrl == null ? "" : cancelOutboundUrl.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public WmsTaskDispatchResult dispatchInbound(WmsProductionInbound inbound, String locationCode) {
        if (inboundUrl.isEmpty()) {
            log.info("未配置 WCS 地址（wms.sync.wcs.inbound-url），任务[{}]按模拟受理处理",
                    inbound.getWcsTaskNo());
            return WmsTaskDispatchResult.okMock();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", inbound.getWcsTaskNo());
            payload.put("locationCode", locationCode);
            payload.put("carrierCode", inbound.getCarrierCode());
            payload.put("materialCode", inbound.getMaterialCode());
            payload.put("productType", inbound.getProductType());
            payload.put("quantity", inbound.getQuantity());
            payload.put("batchNo", inbound.getBatchNo());
            payload.put("identType", inbound.getIdentType());

            ResponseEntity<Map> response = restTemplate.postForEntity(inboundUrl,
                    new HttpEntity<>(payload, headers()), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return WmsTaskDispatchResult.fail(null,
                        "WCS 返回非成功状态：" + response.getStatusCode(), true);
            }
            Map<String, Object> body = response.getBody();
            int code = toInt(body.get("code"), -1);
            if (code == 0) {
                return WmsTaskDispatchResult.ok();
            }
            return WmsTaskDispatchResult.fail(code,
                    body.get("msg") == null ? "" : String.valueOf(body.get("msg")), false);
        } catch (Exception e) {
            log.warn("任务[{}]下发 WCS 异常：{}", inbound.getWcsTaskNo(), e.getMessage());
            return WmsTaskDispatchResult.fail(null, "调用 WCS 异常：" + e.getMessage(), true);
        }
    }

    @Override
    public void cancelInbound(WmsProductionInbound inbound, String locationCode) {
        if (cancelUrl.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", inbound.getWcsTaskNo());
            payload.put("carrierCode", inbound.getCarrierCode());
            payload.put("locationCode", locationCode);
            restTemplate.postForEntity(cancelUrl, new HttpEntity<>(payload, headers()), Map.class);
        } catch (Exception e) {
            log.warn("任务[{}]取消 WCS 任务失败（不阻塞关单）：{}", inbound.getWcsTaskNo(), e.getMessage());
        }
    }

    @Override
    public WmsTaskDispatchResult dispatchOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation) {
        if (outboundUrl.isEmpty()) {
            log.info("未配置 WCS 出库地址（wms.sync.wcs.outbound-url），任务[{}]按模拟受理处理",
                    allocation.getTaskNo());
            return WmsTaskDispatchResult.okMock();
        }
        // 与 RMS 网关一致的 3 次退避重试：WCS 偶发超时不应直接判失败转人工
        WmsTaskDispatchResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            last = doDispatchOutbound(order, allocation);
            if (last.isSuccess() || !last.isRetryable() || i == WmsConstants.SYNC_MAX_RETRY) {
                return last;
            }
            sleepBeforeRetry(i, allocation.getTaskNo());
        }
        return last;
    }

    private WmsTaskDispatchResult doDispatchOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", allocation.getTaskNo());
            payload.put("type", "OUTBOUND");
            payload.put("locationCode", allocation.getLocationCode());
            payload.put("containerCode", allocation.getContainerCode());
            payload.put("quantity", allocation.getAllocatedQty());
            payload.put("outboundNo", order.getOutboundNo());
            payload.put("workOrderNo", order.getWorkOrderNo());

            ResponseEntity<Map> response = restTemplate.postForEntity(outboundUrl,
                    new HttpEntity<>(payload, headers()), Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return WmsTaskDispatchResult.fail(null,
                        "WCS 返回非成功状态：" + response.getStatusCode(), true);
            }
            Map<String, Object> body = response.getBody();
            int code = toInt(body.get("code"), -1);
            if (code == 0) {
                return WmsTaskDispatchResult.ok();
            }
            return WmsTaskDispatchResult.fail(code,
                    body.get("msg") == null ? "" : String.valueOf(body.get("msg")), false);
        } catch (Exception e) {
            log.warn("出库任务[{}]下发 WCS 异常：{}", allocation.getTaskNo(), e.getMessage());
            return WmsTaskDispatchResult.fail(null, "调用 WCS 异常：" + e.getMessage(), true);
        }
    }

    @Override
    public void cancelOutbound(WmsOutboundOrder order, WmsOutboundAllocation allocation) {
        if (cancelOutboundUrl.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskNo", allocation.getTaskNo());
            payload.put("type", "OUTBOUND");
            payload.put("locationCode", allocation.getLocationCode());
            payload.put("containerCode", allocation.getContainerCode());
            restTemplate.postForEntity(cancelOutboundUrl, new HttpEntity<>(payload, headers()), Map.class);
        } catch (Exception e) {
            log.warn("出库任务[{}]取消 WCS 任务失败（不阻塞关单）：{}",
                    allocation.getTaskNo(), e.getMessage());
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!username.isEmpty()) {
            headers.setBasicAuth(username, password);
        }
        return headers;
    }

    private void sleepBeforeRetry(int index, String taskNo) {
        long wait = index < WmsConstants.SYNC_RETRY_BACKOFF_MS.length
                ? WmsConstants.SYNC_RETRY_BACKOFF_MS[index]
                : WmsConstants.SYNC_RETRY_BACKOFF_MS[WmsConstants.SYNC_RETRY_BACKOFF_MS.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WCS 任务[{}]退避等待被中断", taskNo);
        }
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
