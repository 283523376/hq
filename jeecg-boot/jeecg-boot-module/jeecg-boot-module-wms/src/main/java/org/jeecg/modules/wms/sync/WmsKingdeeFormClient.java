package org.jeecg.modules.wms.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶云星空 WebAPI 表单客户端（ExecuteBillQuery / Save / Submit / Delete）。
 *
 * <p>所有调用复用 {@link IWmsKingdeeAuthService} 的会话；不打印请求体（Save 可能含业务数据）与 Cookie。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Component
public class WmsKingdeeFormClient {

    static final String QUERY_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.ExecuteBillQuery.common.kdsvc";
    static final String SAVE_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Save.common.kdsvc";
    static final String SUBMIT_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Submit.common.kdsvc";
    static final String DELETE_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Delete.common.kdsvc";
    static final String VIEW_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc";

    private final WmsKingdeeProperties properties;
    private final IWmsKingdeeAuthService authService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WmsKingdeeFormClient(WmsKingdeeProperties properties, IWmsKingdeeAuthService authService) {
        this.properties = properties;
        this.authService = authService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 30_000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.restTemplate = new RestTemplate(factory);
    }

    /** 通用单据查询。 */
    public WmsKingdeeResult executeBillQuery(String formId, String fieldKeys, String filter, int top) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("FormId", formId);
        data.put("FieldKeys", fieldKeys);
        data.put("FilterString", filter == null ? "" : filter);
        data.put("OrderString", "");
        data.put("TopRowCount", top <= 0 ? 100 : top);
        return post(QUERY_PATH, Map.of("data", data), true);
    }

    /** 保存单据。金蝶要求 FormId 在顶层，业务数据放在 Data 下。 */
    public WmsKingdeeResult save(String formId, Map<String, Object> model) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("NeedUpDateFields", new ArrayList<>());
        data.put("IsDeleteEntry", true);
        data.put("IsEntryBatchFill", false);
        data.put("Model", model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", formId);
        body.put("Data", data);
        return post(SAVE_PATH, body, false);
    }

    /**
     * 提交单据。金蝶 Submit 的参数是 formid + data，
     * data 内放 Numbers；只传顶层 Numbers 会报「接口参数data不能为空」。
     */
    public WmsKingdeeResult submit(String formId, List<String> numbers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", formId);
        body.put("data", Map.of("Numbers", numbers));
        return post(SUBMIT_PATH, body, false);
    }

    /** 删除单据（验收清理用），报文与 Submit 相同。 */
    public WmsKingdeeResult delete(String formId, List<String> numbers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", formId);
        body.put("data", Map.of("Numbers", numbers));
        return post(DELETE_PATH, body, false);
    }

    /**
     * 查看单据（含基础资料）。报文与 Submit 同构：顶层 FormId + data.Number。
     * 成功时 raw 为完整单据 JSON（Result.Result 节点），供调用方解析弹性域等复杂字段。
     */
    public WmsKingdeeResult view(String formId, String number) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", formId);
        body.put("data", Map.of("Number", number));
        return post(VIEW_PATH, body, false);
    }

    @SuppressWarnings("unchecked")
    private WmsKingdeeResult post(String path, Map<String, Object> body, boolean query) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!authService.applyAuth(headers)) {
            return WmsKingdeeResult.fail("金蝶鉴权失败");
        }
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    join(properties.getBaseUrl(), path), new HttpEntity<>(body, headers), String.class);
            String raw = response.getBody() == null ? "" : response.getBody();
            if (!response.getStatusCode().is2xxSuccessful()) {
                return WmsKingdeeResult.fail("金蝶返回非成功状态：" + response.getStatusCode(), raw);
            }
            if (query) {
                return parseQuery(raw);
            }
            return parseOperator(raw);
        } catch (HttpStatusCodeException e) {
            log.warn("金蝶接口 {} 返回 {}：{}", path, e.getStatusCode(), abbreviate(e.getResponseBodyAsString()));
            return WmsKingdeeResult.fail("金蝶接口失败：" + e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("金蝶接口 {} 异常：{}", path, e.getMessage());
            return WmsKingdeeResult.fail("调用金蝶异常：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private WmsKingdeeResult parseQuery(String raw) {
        // 错误响应也可能是数组，内部包一层 Result.ResponseStatus；先识别失败
        if (raw.contains("\"IsSuccess\":false") || raw.contains("\"IsSuccess\": false")) {
            return WmsKingdeeResult.fail(firstError(raw), raw);
        }
        try {
            List<List<Object>> rows = objectMapper.readValue(raw, new TypeReference<List<List<Object>>>() { });
            return WmsKingdeeResult.ok(raw, rows, null, null);
        } catch (Exception e) {
            return WmsKingdeeResult.fail("解析金蝶查询结果失败：" + e.getMessage(), raw);
        }
    }

    @SuppressWarnings("unchecked")
    private WmsKingdeeResult parseOperator(String raw) {
        try {
            Map<String, Object> root = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = root.get("Result") instanceof Map
                    ? (Map<String, Object>) root.get("Result") : null;
            if (result == null) {
                return WmsKingdeeResult.fail("金蝶返回缺少 Result", raw);
            }
            Map<String, Object> status = result.get("ResponseStatus") instanceof Map
                    ? (Map<String, Object>) result.get("ResponseStatus") : null;
            boolean success = status != null && Boolean.TRUE.equals(status.get("IsSuccess"));
            if (!success) {
                return WmsKingdeeResult.fail(firstError(raw), raw);
            }
            String number = result.get("Number") == null ? null : String.valueOf(result.get("Number"));
            String id = result.get("Id") == null ? null : String.valueOf(result.get("Id"));
            return WmsKingdeeResult.ok(raw, null, number, id);
        } catch (Exception e) {
            return WmsKingdeeResult.fail("解析金蝶返回失败：" + e.getMessage(), raw);
        }
    }

    @SuppressWarnings("unchecked")
    private String firstError(String raw) {
        try {
            Map<String, Object> root = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = (Map<String, Object>) root.get("Result");
            Map<String, Object> status = (Map<String, Object>) result.get("ResponseStatus");
            List<Map<String, Object>> errors = (List<Map<String, Object>>) status.get("Errors");
            if (errors != null && !errors.isEmpty()) {
                return String.valueOf(errors.get(0).get("Message"));
            }
        } catch (Exception ignored) {
            // 数组形态的错误响应：退回原始报文前 200 字
        }
        return abbreviate(raw);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(已截断)";
    }

    private String join(String base, String path) {
        String normalized = base == null ? "" : base.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase().endsWith("/k3cloud")) {
            normalized = normalized.substring(0, normalized.length() - "/k3cloud".length());
        }
        return normalized + path;
    }
}
