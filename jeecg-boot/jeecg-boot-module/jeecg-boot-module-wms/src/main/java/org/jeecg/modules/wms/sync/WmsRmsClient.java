package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
 * @Description: 米塔 RMS HTTP 客户端（RMS任务接口文档 V1.6）。
 *
 * <p>统一 HTTP/POST + application/json，连接/读取超时默认 <b>30 秒</b>
 * （{@code wms.sync.rms.timeout-ms} 可覆盖）。只负责协议与报文，不判断业务幂等；
 * 错误码分类交给 {@link WmsRmsErrorCode} 与网关层。
 *
 * <p>已封装：
 * <ul>
 *   <li>POST /api/v2/task/inbound —— 入库任务下发</li>
 *   <li>POST /api/v2/task/inbound/force —— 工作站强制入库</li>
 *   <li>POST /api/v2/task/cancel —— 任务取消</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Component
public class WmsRmsClient {

    /** 默认超时：30 秒（需求方口径）。 */
    public static final int DEFAULT_TIMEOUT_MS = 30_000;

    private static final String PATH_INBOUND = "/api/v2/task/inbound";
    private static final String PATH_INBOUND_FORCE = "/api/v2/task/inbound/force";
    private static final String PATH_OUTBOUND = "/api/v2/task/outbound";
    private static final String PATH_OUTBOUND_PRIORITY = "/api/v2/task/outbound/priority/adjust";
    private static final String PATH_CANCEL = "/api/v2/task/cancel";

    private final String baseUrl;
    private final RestTemplate restTemplate;

    @Autowired
    public WmsRmsClient(@Value("${wms.sync.rms.url:}") String baseUrl,
                        @Value("${wms.sync.rms.timeout-ms:" + DEFAULT_TIMEOUT_MS + "}") int timeoutMs) {
        this(baseUrl, timeoutMs, buildRestTemplate(timeoutMs));
    }

    /** 仅供单测注入 MockRestServiceServer 绑定的 RestTemplate。 */
    WmsRmsClient(String baseUrl, int timeoutMs, RestTemplate restTemplate) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    /**
     * 入库任务下发。
     *
     * @param taskNo        WMS 生成的任务号
     * @param containerCode 料箱/托盘号
     * @param stationCode   工作站编码
     * @param warehouse     仓库名（默认 MITA）
     * @param priority      优先级 1~5，可为 null
     */
    public WmsRmsResponse inbound(String taskNo, String containerCode, String stationCode,
                                  String warehouse, Integer priority) {
        if (oConvertUtils.isEmpty(taskNo)) {
            return WmsRmsResponse.paramError("taskNo 不能为空");
        }
        if (oConvertUtils.isEmpty(containerCode)) {
            return WmsRmsResponse.paramError("containerCode 不能为空");
        }
        if (oConvertUtils.isEmpty(stationCode)) {
            return WmsRmsResponse.paramError("stationCode 不能为空");
        }
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("containerCode", containerCode.trim());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("warehouse", oConvertUtils.isEmpty(warehouse) ? "MITA" : warehouse.trim());
        payload.put("taskNo", taskNo.trim());
        payload.put("stationCode", stationCode.trim());
        payload.put("container", container);
        if (priority != null) {
            payload.put("priority", priority);
        }
        return post(PATH_INBOUND, payload);
    }

    /**
     * 工作站强制入库：工作站内的容器锁定，并呼叫机器人前往对接。
     *
     * @param stationCode 工作站编码（必填）
     * @param taskNo      任务号（可空）
     * @param containers  容器编码列表（可空）
     */
    public WmsRmsResponse inboundForce(String stationCode, String taskNo, List<String> containers) {
        if (oConvertUtils.isEmpty(stationCode)) {
            return WmsRmsResponse.paramError("stationCode 不能为空");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stationCode", stationCode.trim());
        if (oConvertUtils.isNotEmpty(taskNo)) {
            payload.put("taskNo", taskNo.trim());
        }
        if (containers != null && !containers.isEmpty()) {
            payload.put("containers", containers);
        }
        return post(PATH_INBOUND_FORCE, payload);
    }

    /**
     * 出库任务下发（V1.6 §3.1）。
     *
     * @param priority 顶层优先级 1~5，可为 null
     * @param details  出库明细（taskNo + containerCode 必填）
     */
    public WmsRmsResponse outbound(Integer priority, List<WmsRmsOutboundTask> details) {
        if (details == null || details.isEmpty()) {
            return WmsRmsResponse.paramError("details 不能为空");
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (WmsRmsOutboundTask detail : details) {
            if (oConvertUtils.isEmpty(detail.getTaskNo())) {
                return WmsRmsResponse.paramError("taskNo 不能为空");
            }
            if (oConvertUtils.isEmpty(detail.getContainerCode())) {
                return WmsRmsResponse.paramError("containerCode 不能为空");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskNo", detail.getTaskNo().trim());
            item.put("containerCode", detail.getContainerCode().trim());
            if (detail.getPriority() != null) {
                item.put("priority", detail.getPriority());
            }
            if (detail.getStationCodes() != null && !detail.getStationCodes().isEmpty()) {
                item.put("stationCodes", detail.getStationCodes());
            }
            items.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (priority != null) {
            payload.put("priority", priority);
        }
        payload.put("details", items);
        return post(PATH_OUTBOUND, payload);
    }

    /** 出库任务优先级调整（V1.6 §3.2）：containerCode/taskNo/priority 均必填。 */
    public WmsRmsResponse adjustOutboundPriority(List<WmsRmsOutboundTask> details) {
        if (details == null || details.isEmpty()) {
            return WmsRmsResponse.paramError("details 不能为空");
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (WmsRmsOutboundTask detail : details) {
            if (oConvertUtils.isEmpty(detail.getTaskNo()) || oConvertUtils.isEmpty(detail.getContainerCode())
                    || detail.getPriority() == null) {
                return WmsRmsResponse.paramError("taskNo/containerCode/priority 均为必填");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("containerCode", detail.getContainerCode().trim());
            item.put("taskNo", detail.getTaskNo().trim());
            item.put("priority", detail.getPriority());
            items.add(item);
        }
        return post(PATH_OUTBOUND_PRIORITY, Map.of("details", items));
    }

    /** 任务取消（尽力而为，调用方自行决定是否忽略失败）。 */
    public WmsRmsResponse cancel(String taskNo, String containerCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("containerCode", containerCode);
        detail.put("taskNo", taskNo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "INBOUND");
        payload.put("details", List.of(detail));
        return post(PATH_CANCEL, payload);
    }

    /** 统一 POST；未配置地址时返回 notConfigured，传输异常转 transportError，不向上抛。 */
    WmsRmsResponse post(String path, Map<String, Object> payload) {
        if (!isConfigured()) {
            return WmsRmsResponse.notConfigured();
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    join(baseUrl, path), new HttpEntity<>(payload, headers), Map.class);
            int httpStatus = response.getStatusCode().value();
            Map<String, Object> body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()) {
                return WmsRmsResponse.of(-1, "RMS 返回非成功状态：" + response.getStatusCode(),
                        httpStatus, body == null ? null : String.valueOf(body));
            }
            if (body == null) {
                return WmsRmsResponse.of(-1, "RMS 返回空响应", httpStatus, null);
            }
            int code = toInt(body.get("code"), -1);
            String msg = body.get("msg") == null ? "" : String.valueOf(body.get("msg"));
            return WmsRmsResponse.of(code, msg, httpStatus, String.valueOf(body));
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            // RestTemplate 默认把 4xx/5xx 抛成 HttpStatusCodeException；这里保留状态码与响应体
            log.warn("调用 RMS {} 返回非成功状态 {}：{}", path, e.getStatusCode(), e.getResponseBodyAsString());
            return WmsRmsResponse.of(-1, "RMS 返回非成功状态：" + e.getStatusCode(),
                    e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("调用 RMS {} 异常：{}", path, e.getMessage());
            return WmsRmsResponse.transportError("调用 RMS 异常：" + e.getMessage());
        }
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
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
