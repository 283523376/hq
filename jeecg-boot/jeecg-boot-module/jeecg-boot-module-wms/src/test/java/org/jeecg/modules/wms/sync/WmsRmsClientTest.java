package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @Description: RMS HTTP 客户端报文与响应解析单测（30s 超时由构造参数控制）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsRmsClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private WmsRmsClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new WmsRmsClient("http://rms.test", 30000, restTemplate);
    }

    @Test
    void inbound_sendsDocumentedPayload() {
        server.expect(requestTo("http://rms.test/api/v2/task/inbound"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.taskNo").value("T1"))
                .andExpect(jsonPath("$.stationCode").value("ST1"))
                .andExpect(jsonPath("$.warehouse").value("MITA"))
                .andExpect(jsonPath("$.container.containerCode").value("C1"))
                .andExpect(jsonPath("$.priority").value(1))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\"}", MediaType.APPLICATION_JSON));

        WmsRmsResponse response = client.inbound("T1", "C1", "ST1", "MITA", 1);

        assertTrue(response.isSuccess());
        assertEquals(0, response.getCode());
        server.verify();
    }

    @Test
    void forceInbound_sendsStationTaskAndContainers() {
        server.expect(requestTo("http://rms.test/api/v2/task/inbound/force"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.stationCode").value("ST1"))
                .andExpect(jsonPath("$.taskNo").value("T1"))
                .andExpect(jsonPath("$.containers[0]").value("C1"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\"}", MediaType.APPLICATION_JSON));

        WmsRmsResponse response = client.inboundForce("ST1", "T1", List.of("C1"));

        assertTrue(response.isSuccess());
        server.verify();
    }

    @Test
    void errorCode_isParsedWithMessage() {
        server.expect(requestTo("http://rms.test/api/v2/task/inbound"))
                .andRespond(withSuccess("{\"code\":300006,\"msg\":\"容器编码已存在\"}",
                        MediaType.APPLICATION_JSON));

        WmsRmsResponse response = client.inbound("T1", "C1", "ST1", "MITA", 1);

        assertFalse(response.isSuccess());
        assertEquals(300006, response.getCode());
        assertEquals("容器编码已存在", response.getMessage());
    }

    @Test
    void non2xx_isReportedAsHttpFailure() {
        server.expect(requestTo("http://rms.test/api/v2/task/inbound"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        WmsRmsResponse response = client.inbound("T1", "C1", "ST1", "MITA", 1);

        assertFalse(response.isSuccess());
        assertEquals(500, response.getHttpStatus());
        assertTrue(response.getMessage().contains("500"));
    }

    @Test
    void transportException_isReported() {
        server.expect(requestTo("http://rms.test/api/v2/task/inbound"))
                .andRespond(withException(new java.net.SocketTimeoutException("Read timed out")));

        WmsRmsResponse response = client.inbound("T1", "C1", "ST1", "MITA", 1);

        assertFalse(response.isSuccess());
        assertTrue(response.isTransportError());
        assertTrue(response.getMessage().contains("Read timed out"));
    }

    @Test
    void unconfiguredBaseUrl_returnsNotConfigured() {
        WmsRmsClient offline = new WmsRmsClient("", 30000, restTemplate);

        assertFalse(offline.isConfigured());
        WmsRmsResponse response = offline.inbound("T1", "C1", "ST1", "MITA", 1);
        assertFalse(response.isConfigured());
        assertFalse(response.isSuccess());
        server.verify();
    }

    @Test
    void missingStationCode_returnsParamErrorWithoutCall() {
        WmsRmsResponse response = client.inbound("T1", "C1", "", "MITA", 1);

        assertFalse(response.isSuccess());
        assertEquals(WmsRmsErrorCode.PARAM_ERROR, response.getCode());
        assertTrue(response.getMessage().contains("stationCode"));
        server.verify();
    }
}
