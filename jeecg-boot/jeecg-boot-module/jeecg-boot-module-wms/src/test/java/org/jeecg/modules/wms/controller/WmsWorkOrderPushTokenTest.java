package org.jeecg.modules.wms.controller;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: D8——MES 推送（{@code POST /wms/work-order/push}）的共享令牌校验。
 *
 * <p>机器回调端点与 RMS/WCS 同一防线：令牌错/缺一律拒绝且不触达业务服务，
 * 令牌正确才放行。RMS/WCS 已有 WmsCallbackControllerTest#badToken_isRejected，
 * MES 侧此前零覆盖（不变量对照 2026-11-11 补齐）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-11
 */
class WmsWorkOrderPushTokenTest {

    private IWmsWorkOrderService service;
    private WmsWorkOrderController controller;

    @BeforeEach
    void setUp() {
        service = mock(IWmsWorkOrderService.class);
        controller = new WmsWorkOrderController();
        WmsTestSupport.inject(controller, "service", service);
        WmsTestSupport.inject(controller, "pushToken", "mes-token");
    }

    @Test
    void wrongToken_isRejectedWithoutTouchingService() {
        Result<WmsWorkOrder> result = controller.push("wrong", new WmsWorkOrderPushDTO());

        assertFalse(result.isSuccess());
        verify(service, never()).push(any());
    }

    @Test
    void missingToken_isRejectedWithoutTouchingService() {
        Result<WmsWorkOrder> result = controller.push(null, new WmsWorkOrderPushDTO());

        assertFalse(result.isSuccess());
        verify(service, never()).push(any());
    }

    @Test
    void validToken_reachesService() {
        when(service.push(any())).thenReturn(new WmsWorkOrder().setWorkOrderNo("MO1"));

        Result<WmsWorkOrder> result = controller.push("mes-token", new WmsWorkOrderPushDTO());

        assertTrue(result.isSuccess());
        verify(service).push(any());
    }
}
