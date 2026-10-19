package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.sync.IWmsProductionInboundSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库三方同步重试编排单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionSyncServiceImplTest {

    private IWmsProductionInboundSyncGateway gateway;
    private WmsProductionInboundMapper inboundMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsProductionSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = mock(IWmsProductionInboundSyncGateway.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsProductionSyncServiceImpl(gateway, inboundMapper, sysBaseAPI);
        service.setBackoffMsForTest(new long[] {0L, 0L, 0L});

        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        record.setInboundNo("SC1");
        record.setWorkOrderNo("WO1");
        when(inboundMapper.selectById("id1")).thenReturn(record);
    }

    @Test
    void sync_allTargetsUnconfigured_isMockSuccess() {
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.okMock());
        assertTrue(service.syncInbound("id1", true).isSuccess());
    }

    @Test
    void sync_failure_retriesAndAlerts() {
        when(gateway.sync(any())).thenReturn(WmsOrderSyncResult.fail("ERP 500"));
        WmsOrderSyncAttempt attempt = service.syncInbound("id1", true);
        assertFalse(attempt.isSuccess());
        assertEquals(3, attempt.getRetryCount());
        verify(gateway, org.mockito.Mockito.times(4)).sync(any());
        verify(sysBaseAPI).sendSysAnnouncement(any());
    }

    @Test
    void sync_missingRecord_returnsFailure() {
        when(inboundMapper.selectById("none")).thenReturn(null);
        assertFalse(service.syncInbound("none", true).isSuccess());
    }
}
