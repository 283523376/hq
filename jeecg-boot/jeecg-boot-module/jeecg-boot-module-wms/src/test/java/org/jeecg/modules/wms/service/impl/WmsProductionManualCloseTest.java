package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.dto.WmsProductionManualCompleteDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 转人工完成与关单单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionManualCloseTest {

    private WmsProductionInboundMapper inboundMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsLocationMapper locationMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsProductionInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundMapper = mock(WmsProductionInboundMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", inboundMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
    }

    private WmsProductionInbound record(String productType, String status) {
        WmsProductionInbound r = new WmsProductionInbound();
        r.setId("id1");
        r.setInboundNo("SC1");
        r.setProductType(productType);
        r.setStatus(status);
        r.setTaskStatus("失败");
        r.setMaterialId("mat1");
        r.setLocationId("loc1");
        r.setBatchNo("B1");
        r.setCarrierCode("C1");
        r.setGoodQty(new BigDecimal("8"));
        r.setBadQty(BigDecimal.ZERO);
        return r;
    }

    private WmsProductionManualCompleteDTO manual(String carrier) {
        WmsProductionManualCompleteDTO dto = new WmsProductionManualCompleteDTO();
        dto.setCarrierCode(carrier);
        dto.setRemark("人工确认完成");
        return dto;
    }

    @Test
    void manualComplete_fromException_writesInventoryAndBindsCarrier() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "异常待人工"));
        when(inboundMapper.update(any(), any())).thenReturn(1);

        service.manualComplete("id1", manual("NEW-RFID"));

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsProductionInbound>>
                captor = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(inboundMapper, org.mockito.Mockito.atLeastOnce()).update(ArgumentMatchers.isNull(), captor.capture());
        UpdateWrapper<WmsProductionInbound> wrapper =
                (UpdateWrapper<WmsProductionInbound>) captor.getAllValues().get(0);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("已入库"));
        assertTrue(wrapper.getSqlSet().contains("manual_remark"));
        assertTrue(wrapper.getSqlSet().contains("task_error = NULL"));
        verify(inventoryMapper).upsertInventory(any(WmsInventory.class));
    }

    @Test
    void manualComplete_fromPending_isRejected() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "待上架"));
        when(inboundMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.manualComplete("id1", manual(null)));
        assertEquals("当前状态不允许转人工完成", e.getMessage());
        verify(inventoryMapper, never()).upsertInventory(any());
    }

    @Test
    void close_releasesStereoLocation_andDoesNotWriteInventory() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "异常待人工"));
        when(inboundMapper.update(any(), any())).thenReturn(1);
        when(locationMapper.update(any(), any())).thenReturn(1);

        service.close("id1", "设备永久故障");

        verify(inboundMapper).update(any(), ArgumentMatchers.<UpdateWrapper<WmsProductionInbound>>argThat(
                w -> w.getParamNameValuePairs().containsValue("已关闭")));
        verify(locationMapper).update(any(), ArgumentMatchers.argThat(
                w -> ((UpdateWrapper<?>) w).getParamNameValuePairs().containsValue("空闲")));
        verify(inventoryMapper, never()).upsertInventory(any());
    }

    @Test
    void close_inboundRecord_isRejected() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "已入库"));
        when(inboundMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.close("id1", "x"));
        assertEquals("当前状态不允许关单", e.getMessage());
    }

    @Test
    void close_withoutReason_isRejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.close("id1", "  "));
        assertEquals("关单原因不能为空", e.getMessage());
    }
}
