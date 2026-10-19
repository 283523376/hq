package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 入库单 ERP 同步网关单测。
 *
 * <p>与订单网关的差异（spec §7）：未配置地址时**模拟成功**，保证收料流程可闭环演示。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class HttpWmsInboundErpSyncGatewayTest {

    private WmsInboundOrder order() {
        WmsInboundOrder o = new WmsInboundOrder();
        o.setOrderNo("SL202610190001");
        o.setPurchaseOrderNo("PO001");
        o.setSupplierName("供应商一");
        return o;
    }

    private WmsInboundDetail detail() {
        WmsInboundDetail d = new WmsInboundDetail();
        d.setMaterialCode("M001");
        d.setBatchNo("B001");
        d.setActualQuantity(new BigDecimal("10"));
        return d;
    }

    /** 未配置地址 → 模拟成功（而不是像订单网关那样失败）。 */
    @Test
    void gatewayMockSucceedsWhenUrlUnconfigured() {
        HttpWmsInboundErpSyncGateway gateway = new HttpWmsInboundErpSyncGateway("");

        WmsOrderSyncResult result = gateway.sync(order(), Collections.singletonList(detail()));

        assertTrue(result.isSuccess());
        assertTrue(result.getErrorMessage() == null || result.getErrorMessage().contains("mock")
                        || result.getErrorMessage().isEmpty(),
                "模拟成功不应携带失败原因：" + result.getErrorMessage());
    }

    /** null 同样按未配置处理。 */
    @Test
    void gatewayMockSucceedsWhenUrlIsNull() {
        HttpWmsInboundErpSyncGateway gateway = new HttpWmsInboundErpSyncGateway(null);

        assertTrue(gateway.sync(order(), Collections.singletonList(detail())).isSuccess());
    }

    /** 配置了地址但不可达 → 优雅失败，交给重试编排。 */
    @Test
    void gatewayPostsWhenConfigured() {
        HttpWmsInboundErpSyncGateway gateway = new HttpWmsInboundErpSyncGateway("http://127.0.0.1:1/erp/inbound");

        WmsOrderSyncResult result = gateway.sync(order(), Collections.singletonList(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }
}
