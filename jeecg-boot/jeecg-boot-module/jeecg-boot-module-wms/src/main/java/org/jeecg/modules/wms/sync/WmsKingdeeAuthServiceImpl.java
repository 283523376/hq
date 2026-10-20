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
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶云星空第三方登录（LoginByAppSecret）会话缓存。
 *
 * <p>安全约束（需求方明确要求）：
 * <ul>
 *   <li>appSecret 只从服务端配置/环境变量读取，不下发、不打印、不写入异常；</li>
 *   <li>base-url 必须是 HTTPS，否则拒绝登录；</li>
 *   <li>日志只输出脱敏 appId，绝不输出 appSecret、请求体或会话 Cookie。</li>
 * </ul>
 *
 * <p>会话有效期按金蝶默认 30 分钟估算，本实现 25 分钟主动刷新；
 * 业务接口返回 401/403 时由网关调用 {@link #invalidate()} 后重试一次。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Slf4j
@Service
public class WmsKingdeeAuthServiceImpl implements IWmsKingdeeAuthService {

    static final String LOGIN_PATH =
            "/k3cloud/Kingdee.BOS.WebApi.ServicesStub.AuthService.LoginByAppSecret.common.kdsvc";
    private static final long SESSION_TTL_MS = 25 * 60 * 1000L;

    private final String baseUrl;
    private final String acctId;
    private final String username;
    private final String appId;
    private final String appSecret;
    private final int lcid;
    /** 仅测试环境：显式允许 HTTP（默认 false，生产必须 HTTPS）。 */
    private final boolean allowHttp;
    private final RestTemplate restTemplate;

    private volatile String cookie;
    private volatile long expiresAt;

    @Autowired
    public WmsKingdeeAuthServiceImpl(
            @Value("${wms.sync.erp.kingdee.base-url:}") String baseUrl,
            @Value("${wms.sync.erp.kingdee.acct-id:}") String acctId,
            @Value("${wms.sync.erp.kingdee.username:}") String username,
            @Value("${wms.sync.erp.kingdee.app-id:}") String appId,
            @Value("${wms.sync.erp.kingdee.app-secret:}") String appSecret,
            @Value("${wms.sync.erp.kingdee.lcid:2052}") int lcid,
            @Value("${wms.sync.erp.kingdee.allow-http:false}") boolean allowHttp,
            @Value("${wms.sync.erp.kingdee.timeout-ms:30000}") int timeoutMs) {
        this(baseUrl, acctId, username, appId, appSecret, lcid, allowHttp, buildRestTemplate(timeoutMs));
    }

    /** 仅供单测注入 MockRestServiceServer 绑定的 RestTemplate。 */
    WmsKingdeeAuthServiceImpl(String baseUrl, String acctId, String username, String appId,
                              String appSecret, int lcid, boolean allowHttp, RestTemplate restTemplate) {
        this.baseUrl = trim(baseUrl);
        this.acctId = trim(acctId);
        this.username = trim(username);
        this.appId = trim(appId);
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.lcid = lcid;
        this.allowHttp = allowHttp;
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !acctId.isEmpty() && !username.isEmpty()
                && !appId.isEmpty() && !appSecret.isEmpty();
    }

    @Override
    public synchronized boolean applyAuth(HttpHeaders headers) {
        if (!isConfigured()) {
            // 未配置金蝶：不附加 Cookie，也不阻塞非金蝶地址的同步
            return true;
        }
        boolean https = baseUrl.toLowerCase().startsWith("https://");
        if (!https && !allowHttp) {
            log.error("金蝶 ERP 地址必须使用 HTTPS（当前配置未通过安全检查），拒绝登录");
            return false;
        }
        if (!https) {
            log.warn("金蝶测试环境允许 HTTP（wms.sync.erp.kingdee.allow-http=true），生产环境必须改为 HTTPS");
        }
        String current = cookie;
        if (current == null || System.currentTimeMillis() >= expiresAt) {
            current = login();
            if (current == null) {
                return false;
            }
            cookie = current;
            expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        }
        headers.set(HttpHeaders.COOKIE, current);
        return true;
    }

    @Override
    public synchronized void invalidate() {
        cookie = null;
        expiresAt = 0;
    }

    @Override
    public String maskedAppId() {
        if (appId.isEmpty()) {
            return "****";
        }
        int keep = Math.min(6, appId.length());
        return appId.substring(0, keep) + "****";
    }

    /** 登录并返回 Cookie；失败返回 null（不抛异常，不记录敏感信息）。 */
    private String login() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acctID", acctId);
        body.put("username", username);
        body.put("appid", appId);
        body.put("appsecret", appSecret);
        body.put("lcid", lcid);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    join(baseUrl, LOGIN_PATH), new HttpEntity<>(body, headers), Map.class);
            Map<String, Object> result = response.getBody();
            int resultType = result == null ? -1 : toInt(result.get("LoginResultType"), -1);
            if (resultType != 1) {
                // 只记录结果与对方 Message，绝不回显 appSecret/请求体
                log.warn("金蝶登录失败：appId={}，LoginResultType={}，Message={}",
                        maskedAppId(), resultType, result == null ? "" : result.get("Message"));
                return null;
            }
            String sessionCookie = extractCookie(response.getHeaders(), result);
            if (sessionCookie == null) {
                log.warn("金蝶登录成功但未返回会话 Cookie：appId={}", maskedAppId());
                return null;
            }
            log.info("金蝶登录成功：appId={}，会话已缓存", maskedAppId());
            return sessionCookie;
        } catch (Exception e) {
            // e.getMessage 可能包含 URL/超时原因，但不会包含 appSecret
            log.warn("金蝶登录异常：appId={}，原因={}", maskedAppId(), e.getMessage());
            return null;
        }
    }

    /** 从 Set-Cookie 与响应体提取会话；只保留 kdservice-sessionid / ASP.NET_SessionId。 */
    private String extractCookie(HttpHeaders headers, Map<String, Object> body) {
        List<String> pairs = new ArrayList<>();
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies != null) {
            for (String setCookie : setCookies) {
                String first = setCookie.split(";", 2)[0].trim();
                if (first.startsWith("kdservice-sessionid=") || first.startsWith("ASP.NET_SessionId=")) {
                    pairs.add(first);
                }
            }
        }
        Object sessionId = body.get("KDSVCSessionId");
        boolean hasKdSession = pairs.stream().anyMatch(p -> p.startsWith("kdservice-sessionid="));
        if (!hasKdSession && sessionId != null && oConvertUtils.isNotEmpty(String.valueOf(sessionId))) {
            pairs.add("kdservice-sessionid=" + sessionId);
        }
        return pairs.isEmpty() ? null : String.join("; ", pairs);
    }

    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private String join(String base, String path) {
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        // 数据中心地址常写成 http(s)://host:port/k3cloud；登录路径本身已含 /k3cloud，避免重复
        if (normalized.toLowerCase().endsWith("/k3cloud")) {
            normalized = normalized.substring(0, normalized.length() - "/k3cloud".length());
        }
        return normalized + path;
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
