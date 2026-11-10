package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @Description: 金蝶 LoginByAppSecret 登录、会话缓存、HTTPS 与脱敏断言。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsKingdeeAuthServiceImplTest {

    private static final String LOGIN_URL = "https://k3cloud.test"
            + WmsKingdeeAuthServiceImpl.LOGIN_PATH;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private WmsKingdeeAuthServiceImpl auth(String baseUrl) {
        return new WmsKingdeeAuthServiceImpl(baseUrl, "acct1", "apiuser",
                "app1", "app-secret-value", 2052, false, restTemplate);
    }

    private WmsKingdeeAuthServiceImpl authAllowHttp(String baseUrl) {
        return new WmsKingdeeAuthServiceImpl(baseUrl, "acct1", "apiuser",
                "app1", "app-secret-value", 2052, true, restTemplate);
    }

    @Test
    void loginSuccess_cachesSessionCookie() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add(HttpHeaders.SET_COOKIE, "kdservice-sessionid=S1; Path=/; HttpOnly");
        server.expect(requestTo(LOGIN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.acctID").value("acct1"))
                .andExpect(jsonPath("$.username").value("apiuser"))
                .andExpect(jsonPath("$.appid").value("app1"))
                .andExpect(jsonPath("$.appsecret").value("app-secret-value"))
                .andExpect(jsonPath("$.lcid").value(2052))
                .andRespond(withSuccess("{\"LoginResultType\":1,\"KDSVCSessionId\":\"S1\"}",
                        MediaType.APPLICATION_JSON).headers(responseHeaders));

        WmsKingdeeAuthServiceImpl service = auth("https://k3cloud.test");
        HttpHeaders first = new HttpHeaders();
        assertTrue(service.applyAuth(first));
        assertEquals("kdservice-sessionid=S1", first.getFirst(HttpHeaders.COOKIE));

        // 第二次直接复用缓存，不应再发登录请求（server 只有一个 expectation）
        HttpHeaders second = new HttpHeaders();
        assertTrue(service.applyAuth(second));
        assertEquals("kdservice-sessionid=S1", second.getFirst(HttpHeaders.COOKIE));
        server.verify();
    }

    @Test
    void loginFailure_returnsFalse() {
        server.expect(requestTo(LOGIN_URL))
                .andRespond(withSuccess("{\"LoginResultType\":-1,\"Message\":\"验证签名失败\"}",
                        MediaType.APPLICATION_JSON));

        assertFalse(auth("https://k3cloud.test").applyAuth(new HttpHeaders()));
        server.verify();
    }

    @Test
    void httpBaseUrl_isRejectedWithoutCall() {
        assertFalse(auth("http://insecure.test").applyAuth(new HttpHeaders()));
        server.verify();
    }

    @Test
    void httpBaseUrl_allowedWhenExplicitlyEnabled_andK3CloudSuffixNotDuplicated() {
        // base 已带 /k3cloud，实现会去掉重复前缀；LOGIN_PATH 自带 /k3cloud
        server.expect(requestTo("http://219.139.47.222:2021"
                        + WmsKingdeeAuthServiceImpl.LOGIN_PATH))
                .andRespond(withSuccess("{\"LoginResultType\":1,\"KDSVCSessionId\":\"S1\"}",
                        MediaType.APPLICATION_JSON));

        WmsKingdeeAuthServiceImpl service = authAllowHttp("http://219.139.47.222:2021/k3cloud");
        assertTrue(service.applyAuth(new HttpHeaders()));
        server.verify();
    }

    @Test
    void notConfigured_allowsNoAuth() {
        WmsKingdeeAuthServiceImpl empty = new WmsKingdeeAuthServiceImpl(
                "", "", "", "", "", 2052, false, restTemplate);
        assertFalse(empty.isConfigured());
        assertTrue(empty.applyAuth(new HttpHeaders()));
        server.verify();
    }

    @Test
    void maskedAppId_neverLeaksSecret() {
        WmsKingdeeAuthServiceImpl service = auth("https://k3cloud.test");
        String masked = service.maskedAppId();
        assertTrue(masked.contains("****"));
        assertFalse(masked.contains("app-secret-value"));
    }

    @Test
    void invalidate_forcesRelogin() {
        server.expect(requestTo(LOGIN_URL))
                .andRespond(withSuccess("{\"LoginResultType\":1,\"KDSVCSessionId\":\"S1\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOGIN_URL))
                .andRespond(withSuccess("{\"LoginResultType\":1,\"KDSVCSessionId\":\"S2\"}",
                        MediaType.APPLICATION_JSON));

        WmsKingdeeAuthServiceImpl service = auth("https://k3cloud.test");
        HttpHeaders first = new HttpHeaders();
        assertTrue(service.applyAuth(first));
        service.invalidate();
        HttpHeaders second = new HttpHeaders();
        assertTrue(service.applyAuth(second));

        assertEquals("kdservice-sessionid=S1", first.getFirst(HttpHeaders.COOKIE));
        assertEquals("kdservice-sessionid=S2", second.getFirst(HttpHeaders.COOKIE));
        assertNotEquals(first.getFirst(HttpHeaders.COOKIE), second.getFirst(HttpHeaders.COOKIE));
        server.verify();
    }
}
