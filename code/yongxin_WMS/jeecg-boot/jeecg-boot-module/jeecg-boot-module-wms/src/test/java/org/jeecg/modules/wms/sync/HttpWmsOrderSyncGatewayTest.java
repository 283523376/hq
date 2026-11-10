package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: HTTP 同步网关单测
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class HttpWmsOrderSyncGatewayTest {

    /**
     * Review Focus #1：全新部署默认没配 wms.sync.erp.url。
     * 此时必须"优雅地报告失败"——不能抛异常、不能 NPE。
     */
    @Test
    void sync_shouldFailGracefullyWhenUrlNotConfigured() {
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway("");

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置"),
                "失败原因应说明未配置，实际为：" + result.getErrorMessage());
    }

    /** URL 为 null 同样要优雅失败。 */
    @Test
    void sync_shouldFailGracefullyWhenUrlIsNull() {
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway(null);

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
    }

    /** 地址不可达时必须捕获异常并转成失败结果，而不是把异常抛给调用方。 */
    @Test
    void sync_shouldConvertConnectionFailureIntoResult() {
        // 保留端口 1 上不会有服务监听，连接必然失败
        HttpWmsOrderSyncGateway gateway = new HttpWmsOrderSyncGateway("http://127.0.0.1:1/erp/order");

        WmsOrderSyncResult result = gateway.sync(new WmsOrder().setOrderNo("PO001"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    /** 结果对象的成功/失败工厂语义。 */
    @Test
    void resultFactories_shouldBehave() {
        assertTrue(WmsOrderSyncResult.ok().isSuccess());
        assertFalse(WmsOrderSyncResult.fail("boom").isSuccess());
        assertTrue(WmsOrderSyncResult.fail("boom").getErrorMessage().contains("boom"));
    }
}
