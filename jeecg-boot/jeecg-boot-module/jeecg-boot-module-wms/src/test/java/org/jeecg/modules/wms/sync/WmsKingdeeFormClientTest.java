package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * @Description: 金蝶查询翻页参数单测——5 参重载带 StartRow，4 参委托 StartRow=0（spec 2026-10-26）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeeFormClientTest {

    private MockRestServiceServer server;
    private WmsKingdeeFormClient client;

    @BeforeEach
    void setUp() {
        WmsKingdeeProperties properties = new WmsKingdeeProperties();
        properties.setBaseUrl("http://localhost:9999/k3cloud");
        IWmsKingdeeAuthService authService = mock(IWmsKingdeeAuthService.class);
        when(authService.applyAuth(any())).thenReturn(true);

        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new WmsKingdeeFormClient(properties, authService, restTemplate);
    }

    @Test
    void shouldSendStartRowFromFiveArgOverload() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("ExecuteBillQuery")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"StartRow\":500")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"TopRowCount\":10")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.executeBillQuery("BD_STOCK", "FNumber,FName", "", 10, 500);

        server.verify();
    }

    @Test
    void shouldDefaultStartRowToZeroForFourArgOverload() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("ExecuteBillQuery")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"StartRow\":0")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.executeBillQuery("BD_STOCK", "FNumber,FName", "", 10);

        server.verify();
    }
}
