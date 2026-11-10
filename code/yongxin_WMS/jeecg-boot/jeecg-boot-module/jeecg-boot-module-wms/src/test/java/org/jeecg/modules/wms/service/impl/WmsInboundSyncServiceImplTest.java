package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.sync.WmsInboundErpSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 入库单同步重试编排单测（不启 Spring）。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsInboundSyncServiceImplTest {

    private WmsInboundOrder order() {
        WmsInboundOrder o = new WmsInboundOrder();
        o.setId("ib1");
        o.setOrderNo("SL202610190001");
        o.setPurchaseOrderNo("PO001");
        return o;
    }

    @Test
    void syncServiceRetriesUpToMax() {
        WmsInboundErpSyncGateway gateway = mock(WmsInboundErpSyncGateway.class);
        when(gateway.sync(any(), anyList())).thenReturn(WmsOrderSyncResult.fail("ERP 不可达"));
        WmsInboundSyncServiceImpl service = new WmsInboundSyncServiceImpl(gateway, org.mockito.Mockito.mock(org.jeecg.modules.wms.mapper.WmsInboundDetailMapper.class));
        service.setBackoffMsForTest(new long[] {0L, 0L, 0L});

        WmsOrderSyncAttempt attempt = service.syncInbound(order(), false);

        assertFalse(attempt.isSuccess());
        // 首次 + SYNC_MAX_RETRY 次重试
        verify(gateway, times(WmsConstants.SYNC_MAX_RETRY + 1)).sync(any(), anyList());
        assertEquals(WmsConstants.SYNC_MAX_RETRY, attempt.getRetryCount());
    }

    @Test
    void syncServiceStopsAtFirstSuccess() {
        WmsInboundErpSyncGateway gateway = mock(WmsInboundErpSyncGateway.class);
        when(gateway.sync(any(), anyList())).thenReturn(WmsOrderSyncResult.ok());
        WmsInboundSyncServiceImpl service = new WmsInboundSyncServiceImpl(gateway, org.mockito.Mockito.mock(org.jeecg.modules.wms.mapper.WmsInboundDetailMapper.class));

        WmsOrderSyncAttempt attempt = service.syncInbound(order(), false);

        assertTrue(attempt.isSuccess());
        verify(gateway, times(1)).sync(any(), anyList());
    }

    @Test
    void retryThenSuccessReportsAttemptCount() {
        WmsInboundErpSyncGateway gateway = mock(WmsInboundErpSyncGateway.class);
        when(gateway.sync(any(), anyList()))
                .thenReturn(WmsOrderSyncResult.fail("第一次失败"))
                .thenReturn(WmsOrderSyncResult.ok());
        WmsInboundSyncServiceImpl service = new WmsInboundSyncServiceImpl(gateway, org.mockito.Mockito.mock(org.jeecg.modules.wms.mapper.WmsInboundDetailMapper.class));
        service.setBackoffMsForTest(new long[] {0L, 0L, 0L});

        WmsOrderSyncAttempt attempt = service.syncInbound(order(), false);

        assertTrue(attempt.isSuccess());
        assertEquals(1, attempt.getRetryCount());
    }
}
