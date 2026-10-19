package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备回执单测：幂等完成、执行中/失败状态映射、料箱位置更新。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionInboundCallbackTest {

    private WmsProductionInboundMapper inboundMapper;
    private WmsInventoryMapper inventoryMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsProductionInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundMapper = mock(WmsProductionInboundMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", inboundMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
    }

    private WmsProductionInbound record() {
        WmsProductionInbound r = new WmsProductionInbound();
        r.setId("id1");
        r.setInboundNo("SC1");
        r.setWcsTaskNo("T1");
        r.setMaterialId("mat1");
        r.setLocationId("loc1");
        r.setBatchNo("B1");
        r.setGoodQty(new BigDecimal("8"));
        r.setBadQty(new BigDecimal("2"));
        return r;
    }

    private void stubRunningRecord() {
        when(inboundMapper.selectOne(ArgumentMatchers
                .<Wrapper<WmsProductionInbound>>any())).thenReturn(record());
        when(inboundMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void finished_writesInventoryOnce() {
        stubRunningRecord();
        when(inboundMapper.claimFinished("T1", "STACK-1")).thenReturn(1, 0);

        assertTrue(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));
        assertFalse(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));

        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(captor.capture());
        assertTrue(captor.getValue().getQuantity().compareTo(new BigDecimal("8")) == 0);
    }

    @Test
    void running_onlyMovesToExecuting() {
        stubRunningRecord();
        when(inboundMapper.claimFinished(any(), any())).thenReturn(0);

        assertFalse(service.onTaskFeedback("T1", "C1", "RUNNING", null, null, true));

        verify(inboundMapper).update(any(), ArgumentMatchers.argThat(
                w -> ((UpdateWrapper<?>) w).getParamNameValuePairs().containsValue("执行中")));
        verify(inventoryMapper, never()).upsertInventory(any());
    }

    @Test
    void failed_movesToManual() {
        stubRunningRecord();
        when(inboundMapper.claimFinished(any(), any())).thenReturn(0);

        service.onTaskFeedback("T1", "C1", "FAILED", null, "设备故障", false);

        verify(inboundMapper).update(any(), ArgumentMatchers.argThat(
                w -> ((UpdateWrapper<?>) w).getParamNameValuePairs().containsValue("异常待人工")));
        verify(sysBaseAPI).sendSysAnnouncement(any());
    }

    @Test
    void finished_fromException_isAccepted() {
        WmsProductionInbound r = record();
        r.setStatus("异常待人工");
        when(inboundMapper.selectOne(ArgumentMatchers
                .<Wrapper<WmsProductionInbound>>any())).thenReturn(r);
        when(inboundMapper.claimFinished("T1", "STACK-1")).thenReturn(1);

        assertTrue(service.onTaskFeedback("T1", "C1", "FINISHED", "STACK-1", null, true));
        verify(inventoryMapper).upsertInventory(any());
    }

    @Test
    void containerLocation_updatesStackCode() {
        when(inboundMapper.update(any(), any())).thenReturn(1);
        service.onContainerLocation("C1", "MITA-STACK-01-15");
        verify(inboundMapper).update(any(), ArgumentMatchers.argThat(
                w -> ((UpdateWrapper<?>) w).getSqlSet().contains("stack_code")
                        && ((UpdateWrapper<?>) w).getSqlSegment().contains("LIMIT 1")));
    }

    @Test
    void failed_whenNoRowsUpdated_doesNotAlert() {
        stubRunningRecord();
        when(inboundMapper.claimFinished(any(), any())).thenReturn(0);
        when(inboundMapper.update(any(), any())).thenReturn(0);

        service.onTaskFeedback("T1", "C1", "FAILED", null, "设备故障", false);

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }
}
