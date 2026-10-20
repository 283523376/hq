package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: RMS 网关单测：300001 幂等受理、300006 转人工、300000 退避重试、强制入库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class HttpWmsRmsGatewayTest {

    private WmsRmsClient client;
    private HttpWmsRmsGateway gateway;

    @BeforeEach
    void setUp() {
        client = mock(WmsRmsClient.class);
        when(client.isConfigured()).thenReturn(true);
        gateway = new HttpWmsRmsGateway(client, "ST1", "MITA", 1);
        gateway.setBackoffMsForTest(new long[] {0L, 0L, 0L});
    }

    @Test
    void dispatch_success() {
        when(client.inbound("T1", "C1", "ST1", "MITA", 1)).thenReturn(WmsRmsResponse.of(0, "success"));
        assertTrue(gateway.dispatchInbound("T1", "C1").isSuccess());
    }

    @Test
    void dispatch_300001_isIdempotentSuccess() {
        when(client.inbound("T1", "C1", "ST1", "MITA", 1))
                .thenReturn(WmsRmsResponse.of(300001, "任务号已存在"));
        assertTrue(gateway.dispatchInbound("T1", "C1").isSuccess());
    }

    @Test
    void dispatch_300006_failsWithDescription() {
        when(client.inbound("T1", "C1", "ST1", "MITA", 1))
                .thenReturn(WmsRmsResponse.of(300006, "容器编码已存在"));

        WmsTaskDispatchResult result = gateway.dispatchInbound("T1", "C1");

        assertFalse(result.isSuccess());
        assertEquals(300006, result.getErrorCode());
        assertTrue(result.getErrorMessage().contains("容器编码已存在"));
        assertFalse(result.isRetryable());
    }

    @Test
    void dispatch_300000_retriesThenSucceeds() {
        when(client.inbound("T1", "C1", "ST1", "MITA", 1))
                .thenReturn(WmsRmsResponse.of(300000, "接口调用失败，可重试"))
                .thenReturn(WmsRmsResponse.of(0, "success"));

        assertTrue(gateway.dispatchInbound("T1", "C1").isSuccess());
        verify(client, times(2)).inbound("T1", "C1", "ST1", "MITA", 1);
    }

    @Test
    void dispatch_unconfigured_isMockAccepted() {
        when(client.isConfigured()).thenReturn(false);
        WmsTaskDispatchResult result = gateway.dispatchInbound("T1", "C1");
        assertTrue(result.isSuccess());
        assertTrue(result.isMocked());
    }

    @Test
    void forceInbound_success() {
        when(client.inboundForce("ST1", "T1", List.of("C1")))
                .thenReturn(WmsRmsResponse.of(0, "success"));

        assertTrue(gateway.forceInbound("T1", "C1").isSuccess());
        verify(client).inboundForce("ST1", "T1", List.of("C1"));
    }

    @Test
    void forceInbound_300012_isAlreadyCalled() {
        when(client.inboundForce("ST1", "T1", List.of("C1")))
                .thenReturn(WmsRmsResponse.of(300012, "任务已呼叫机器人对接"));

        assertTrue(gateway.forceInbound("T1", "C1").isSuccess());
    }

    @Test
    void forceInbound_300003_failsWithDescription() {
        when(client.inboundForce("ST1", "T1", List.of("C1")))
                .thenReturn(WmsRmsResponse.of(300003, "工作站编码不存在"));

        WmsTaskDispatchResult result = gateway.forceInbound("T1", "C1");

        assertFalse(result.isSuccess());
        assertEquals(300003, result.getErrorCode());
        assertTrue(result.getErrorMessage().contains("工作站编码不存在"));
    }
}
