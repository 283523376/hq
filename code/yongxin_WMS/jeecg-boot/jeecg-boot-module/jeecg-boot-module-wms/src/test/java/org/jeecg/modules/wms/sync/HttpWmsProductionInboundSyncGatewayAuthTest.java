package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @Description: ERP/MES 同步携带金蝶会话 Cookie；鉴权失败不发请求。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class HttpWmsProductionInboundSyncGatewayAuthTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private IWmsKingdeeAuthService authService;
    private HttpWmsProductionInboundSyncGateway gateway;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        authService = mock(IWmsKingdeeAuthService.class);
        gateway = new HttpWmsProductionInboundSyncGateway(
                "https://erp.test/inbound", "", "", "", "", "", "", restTemplate);
        WmsTestSupport.inject(gateway, "kingdeeAuthService", authService);
    }

    private WmsProductionInbound record() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setInboundNo("SC1");
        record.setWorkOrderNo("WO1");
        record.setProductType("成品");
        record.setMaterialCode("FG001");
        record.setBatchNo("B1");
        record.setQuantity(java.math.BigDecimal.ONE);
        record.setGoodQty(java.math.BigDecimal.ONE);
        record.setBadQty(java.math.BigDecimal.ZERO);
        return record;
    }

    @Test
    void erpSync_appliesKingdeeCookie() {
        when(authService.applyAuth(any())).thenAnswer(invocation -> {
            HttpHeaders headers = invocation.getArgument(0);
            headers.set(HttpHeaders.COOKIE, "kdservice-sessionid=S1");
            return true;
        });
        server.expect(requestTo("https://erp.test/inbound"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.COOKIE, "kdservice-sessionid=S1"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        WmsOrderSyncResult result = gateway.sync(record());

        assertTrue(result.isSuccess());
        server.verify();
    }

    @Test
    void erpAuthFailure_returnsFailureWithoutRequest() {
        when(authService.applyAuth(any())).thenReturn(false);

        WmsOrderSyncResult result = gateway.sync(record());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("金蝶鉴权失败"));
        server.verify();
    }
}
