package org.jeecg.modules.wms.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    static final String PUSH_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.Push.common.kdsvc";
    static final String VIEW_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.DynamicFormService.View.common.kdsvc";

    private final WmsKingdeeProperties properties;
    private final IWmsKingdeeAuthService authService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生产用构造器。
     *
     * <p>显式 {@code @Autowired}：本类还有第二个包内可见的测试构造器，
     * 不标注时 Spring 没有唯一候选，会在启动/首次注入时报
     * 「No default constructor found」。
     */
    @Autowired
    public WmsKingdeeFormClient(WmsKingdeeProperties properties, IWmsKingdeeAuthService authService) {
        this(properties, authService, newRestTemplate(properties));
    }

    /** 仅供单测注入 RestTemplate（MockRestServiceServer 需要拿到同一实例）。 */
    WmsKingdeeFormClient(WmsKingdeeProperties properties, IWmsKingdeeAuthService authService,
                         RestTemplate restTemplate) {
        this.properties = properties;
        this.authService = authService;
        this.restTemplate = restTemplate;
    }

    private static RestTemplate newRestTemplate(WmsKingdeeProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeout = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 30_000;
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    /** 通用单据查询（单页，从第 0 行开始）。 */
    public WmsKingdeeResult executeBillQuery(String formId, String fieldKeys, String filter, int top) {
        return executeBillQuery(formId, fieldKeys, filter, top, 0);
    }

    /**
     * 通用单据查询（带翻页起点）。
     *
     * <p><b>翻页只能用 {@code Limit}，不能把页大小塞进 {@code TopRowCount}。</b>
     * 真机实测（2026-10-28，BD_MATERIAL / 组织 101）：
     * <ul>
     *   <li>{@code TopRowCount=500 & StartRow=500} → <b>返回空</b>：TopRowCount 会先把结果集
     *       截断到 N 行，StartRow 在这个截断之后才生效，于是第二页永远取不到；</li>
     *   <li>{@code TopRowCount=0 & Limit=3 & StartRow=5000} → 正常返回，
     *       物料同步因此从「只有 500 条」恢复为全量。</li>
     * </ul>
     *
     * @param top      本页最多返回条数（= Limit）
     * @param startRow 起始行偏移
     */
    public WmsKingdeeResult executeBillQuery(String formId, String fieldKeys, String filter, int top,
                                             int startRow) {
        return executeBillQuery(formId, fieldKeys, filter, top, startRow, "");
    }

    /**
     * 带排序的查询。
     *
     * <p><b>翻页必须给稳定排序</b>：金蝶在无 {@code OrderString} 时不保证返回顺序，
     * 分页会出现「上一页的行在下一页又出现、另一些行永远取不到」。
     *
     * @param orderString 排序表达式，如 {@code FModifyDate ASC,FBillNo ASC}；空串=不排序
     */
    public WmsKingdeeResult executeBillQuery(String formId, String fieldKeys, String filter, int top,
                                             int startRow, String orderString) {
        int limit = top <= 0 ? 100 : top;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("FormId", formId);
        data.put("FieldKeys", fieldKeys);
        data.put("FilterString", filter == null ? "" : filter);
        data.put("OrderString", orderString == null ? "" : orderString);
        // 必须为 0：>0 时金蝶会先把结果集截断到该行数，StartRow 再跳过就必然为空
        data.put("TopRowCount", 0);
        data.put("StartRow", Math.max(startRow, 0));
        data.put("Limit", limit);
        return post(QUERY_PATH, Map.of("data", data), true);
    }

    /** 保存单据。金蝶要求 FormId 在顶层，业务数据放在 Data 下。 */
    public WmsKingdeeResult save(String formId, Map<String, Object> model) {
        return save(formId, model, true);
    }

    /**
     * 保存单据（可控是否清空未提交的分录）。
     *
     * <p>下推得到的草稿要**更新**而不是重建：{@code isDeleteEntry=false} 才不会把源单带过来的
     * 其余分录删掉。
     */
    public WmsKingdeeResult save(String formId, Map<String, Object> model, boolean isDeleteEntry) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("NeedUpDateFields", new ArrayList<>());
        // 与金蝶 WebAPI 官方样例对齐：IsDeleteEntry 传字符串 "true"/"false"，
        // 不带 IsEntryBatchFill。注：这两项经实测**不是** Save 失败的成因，
        // 仅为消除与官方样例的差异，便于下次排错时缩小变量。
        data.put("IsDeleteEntry", isDeleteEntry ? "true" : "false");
        data.put("Model", model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", formId);
        body.put("data", data);
        return post(SAVE_PATH, body, false);
    }

    /**
     * 下推：从源单生成目标单草稿（生产订单 → 生产入库单）。
     *
     * <p>为什么必须用它：物料启用 BOM 版本管理时，PRD_INSTOCK 的 FBomId 在 Save 时
     * 被服务端忽略，只能由转换规则从源单带出（2026-10-27 实测，详见 spec）。
     *
     * <p>报文与 {@link #save} 同构：顶层 FormId 填**源单**标识，Data 放 Numbers 等参数。
     * 目标单内码在 {@code ResponseStatus.SuccessEntitys[0].Id}，不在 {@code Result.Id}，
     * 所以这里单独解析。
     */
    public WmsKingdeeResult push(String sourceFormId, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FormId", sourceFormId);
        body.put("Data", data);
        WmsKingdeeResult result = post(PUSH_PATH, body, false);
        if (!result.isSuccess()) {
            return result;
        }
        return parsePushTarget(result);
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
        // 显式声明 UTF-8：报文含中文（如 FNote）时不应依赖客户端默认字符集。
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8));
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

    /** 从下推响应里取目标单内码：{@code Result.ResponseStatus.SuccessEntitys[0].Id}。 */
    @SuppressWarnings("unchecked")
    private WmsKingdeeResult parsePushTarget(WmsKingdeeResult base) {
        String raw = base.getRaw();
        try {
            Map<String, Object> root = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
            Map<String, Object> result = root.get("Result") instanceof Map
                    ? (Map<String, Object>) root.get("Result") : null;
            Map<String, Object> status = result == null ? null
                    : (result.get("ResponseStatus") instanceof Map
                    ? (Map<String, Object>) result.get("ResponseStatus") : null);
            List<Map<String, Object>> entities = status == null ? null
                    : (status.get("SuccessEntitys") instanceof List
                    ? (List<Map<String, Object>>) status.get("SuccessEntitys") : null);
            if (entities == null || entities.isEmpty()) {
                return WmsKingdeeResult.fail("下推未返回目标单内码", raw);
            }
            Map<String, Object> first = entities.get(0);
            String id = first.get("Id") == null ? null : String.valueOf(first.get("Id"));
            if (id == null || "null".equals(id)) {
                return WmsKingdeeResult.fail("下推未返回目标单内码", raw);
            }
            String number = first.get("Number") == null ? null : String.valueOf(first.get("Number"));
            return WmsKingdeeResult.ok(raw, null, number, id);
        } catch (Exception e) {
            return WmsKingdeeResult.fail("解析下推返回失败：" + e.getMessage(), raw);
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
