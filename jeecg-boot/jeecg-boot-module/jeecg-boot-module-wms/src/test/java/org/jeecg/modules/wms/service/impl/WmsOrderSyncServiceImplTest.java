package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 同步重试编排单测（退避时间被置零以免拖慢测试）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderSyncServiceImplTest {

    private WmsOrder order() {
        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        order.setId("1");
        return order;
    }

    /** 构造被测对象，并把退避时间全部置零。 */
    private WmsOrderSyncServiceImpl service(WmsOrderSyncGateway gateway, ISysBaseAPI sysBaseAPI) {
        WmsOrderSyncServiceImpl service = new WmsOrderSyncServiceImpl(gateway, sysBaseAPI);
        service.setBackoffMsForTest(new long[]{0L, 0L, 0L});
        return service;
    }

    /** ① 首次即成功：retryCount=0，只调 1 次。 */
    @Test
    void syncOrder_firstAttemptSucceeds() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.ok());
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertTrue(attempt.isSuccess());
        assertEquals(0, attempt.getRetryCount());
        verify(gateway, times(1)).sync(any());
    }

    /** ② 第 2 次才成功：retryCount=1，共调 2 次。 */
    @Test
    void syncOrder_secondAttemptSucceeds() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any()))
                .thenReturn(WmsOrderSyncResult.fail("第一次失败"))
                .thenReturn(WmsOrderSyncResult.ok());
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertTrue(attempt.isSuccess());
        assertEquals(1, attempt.getRetryCount());
        verify(gateway, times(2)).sync(any());
    }

    /** ③ 4 次全失败：retryCount=3，gateway 恰被调 4 次，errorMessage 为最后一次的原因。 */
    @Test
    void syncOrder_allAttemptsFail() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any()))
                .thenReturn(WmsOrderSyncResult.fail("第1次"))
                .thenReturn(WmsOrderSyncResult.fail("第2次"))
                .thenReturn(WmsOrderSyncResult.fail("第3次"))
                .thenReturn(WmsOrderSyncResult.fail("第4次"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertFalse(attempt.isSuccess());
        assertEquals(WmsConstants.SYNC_MAX_RETRY, attempt.getRetryCount());
        assertEquals("第4次", attempt.getErrorMessage());
        verify(gateway, times(WmsConstants.SYNC_MAX_RETRY + 1)).sync(any());
    }

    /** ④ announce=true 时失败必须发站内消息。 */
    @Test
    void syncOrder_shouldAnnounceWhenEnabled() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        service(gateway, sysBaseAPI).syncOrder(order(), true);

        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any());
    }

    /** ⑤ announce=false（定时补偿路径）时不得发消息，防止每 5 分钟轰炸一次。 */
    @Test
    void syncOrder_shouldNotAnnounceWhenDisabled() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        service(gateway, sysBaseAPI).syncOrder(order(), false);

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }

    /**
     * Review Focus #4：ERP 返回一整页 HTML 时，错误原因必须被截断到列宽 500 以内，
     * 否则写库会因超长报错，反而丢掉失败痕迹。
     */
    @Test
    void syncOrder_shouldTruncateOverlongErrorMessage() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append("x");
        }
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail(huge.toString()));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), false);

        assertTrue(attempt.getErrorMessage().length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH,
                "错误原因长度 " + attempt.getErrorMessage().length() + " 超出了列宽上限");
    }

    /** 告警本身抛异常时，不能反过来把同步流程带崩。 */
    @Test
    void syncOrder_shouldSurviveAnnounceFailure() {
        WmsOrderSyncGateway gateway = mock(WmsOrderSyncGateway.class);
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("挂了"));
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        org.mockito.Mockito.doThrow(new RuntimeException("MQ 挂了"))
                .when(sysBaseAPI).sendSysAnnouncement(any());

        WmsOrderSyncAttempt attempt =
                service(gateway, sysBaseAPI).syncOrder(order(), true);

        assertFalse(attempt.isSuccess());
        assertEquals("挂了", attempt.getErrorMessage());
    }
}
