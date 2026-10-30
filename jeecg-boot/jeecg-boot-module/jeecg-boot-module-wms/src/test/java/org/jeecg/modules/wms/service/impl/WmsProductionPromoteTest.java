package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 工序汇报草稿推进入库（方案 B）：草稿 → 待上架、占立库位、不参与 ERP 回写。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-30
 */
class WmsProductionPromoteTest {

    private WmsProductionInboundMapper inboundMapper;
    private WmsLocationMapper locationMapper;
    private IWmsLocationService locationService;
    private WmsProductionInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundMapper = mock(WmsProductionInboundMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        locationService = mock(IWmsLocationService.class);
        service = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", inboundMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
        WmsTestSupport.inject(service, "inventoryMapper", mock(WmsInventoryMapper.class));
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "locationService", locationService);
        WmsTestSupport.inject(service, "sysBaseAPI", mock(ISysBaseAPI.class));
    }

    private WmsProductionInbound draft() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        record.setInboundNo("GXHB1-0-10");
        record.setWorkOrderNo("MO1012616522");
        record.setProductType(WmsConstants.PRODUCT_TYPE_SEMI);
        record.setStatus(WmsConstants.PROD_STATUS_DRAFT);
        record.setErpEntryKey("GXHB1#0#10");
        record.setErpBillNo("GXHB1");
        return record;
    }

    private void stubFreeLocation(String locationId) {
        WmsLocation location = new WmsLocation();
        location.setId(locationId);
        when(locationService.listFreeByWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO))
                .thenReturn(List.of(location));
        when(locationMapper.update(any(), any(Wrapper.class))).thenReturn(1);
    }

    @Test
    void promote_happyPath_movesToPendingPutaway() {
        when(inboundMapper.selectById("id1")).thenReturn(draft());
        stubFreeLocation("loc1");
        when(inboundMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.promote("id1", "BOX-B-01");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).update(captor.capture(), any(Wrapper.class));
        WmsProductionInbound patch = captor.getValue();
        assertEquals(WmsConstants.PROD_STATUS_PENDING_PUTAWAY, patch.getStatus());
        assertEquals(WmsConstants.TASK_STATUS_NOT_DISPATCHED, patch.getTaskStatus());
        assertEquals(WmsConstants.SYNC_STATUS_NOT_REQUIRED, patch.getSyncStatus(),
                "方案 B：ERP 由车间汇报入库，WMS 推进后不得进入回写队列");
        assertEquals("loc1", patch.getLocationId());
        assertEquals("BOX-B-01", patch.getCarrierCode());
        assertNotNull(patch.getWcsTaskNo());
        assertTrue(patch.getWcsTaskNo().startsWith(WmsConstants.TASK_NO_PREFIX_WCS));
    }

    @Test
    void promote_rejectsNonDraft() {
        WmsProductionInbound record = draft();
        record.setStatus(WmsConstants.PROD_STATUS_INBOUND);
        when(inboundMapper.selectById("id1")).thenReturn(record);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.promote("id1", "BOX-B-01"));

        assertTrue(e.getMessage().contains("当前状态不允许推进"), "实际：" + e.getMessage());
        verify(inboundMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void promote_rejectsCarrierMissing() {
        when(inboundMapper.selectById("id1")).thenReturn(draft());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.promote("id1", "  "));

        assertTrue(e.getMessage().contains("载具码"), "实际：" + e.getMessage());
        verify(inboundMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void promote_doubleCall_idempotent() {
        when(inboundMapper.selectById("id1")).thenReturn(draft());
        stubFreeLocation("loc1");
        // 第二次认领：条件更新影响 0 行（单据已被推进）
        when(inboundMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.promote("id1", "BOX-B-01"));

        assertTrue(e.getMessage().contains("已被推进"), "实际：" + e.getMessage());
    }

    @Test
    void promote_writesSubmitTimeAndOperator() {
        when(inboundMapper.selectById("id1")).thenReturn(draft());
        stubFreeLocation("loc1");
        when(inboundMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.promote("id1", "BOX-B-01");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).update(captor.capture(), any(Wrapper.class));
        // 未下发补偿按 submit_time < 阈值 取候选；推进不写这里，下发失败的单永远补不回来
        assertNotNull(captor.getValue().getSubmitTime(), "推进必须写 submit_time，否则未下发补偿捞不到");
    }

    @Test
    @SuppressWarnings("unchecked")
    void manualComplete_keepsReportSourcedOutOfSyncQueue() {
        WmsProductionInbound exception = draft();
        exception.setStatus(WmsConstants.PROD_STATUS_EXCEPTION);
        when(inboundMapper.selectById("id1")).thenReturn(exception);
        when(inboundMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.manualComplete("id1", null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsProductionInbound>>
                captor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(inboundMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        Object syncValue = captor.getValue().getParamNameValuePairs().entrySet().stream()
                .filter(e -> WmsConstants.SYNC_STATUS_PENDING.equals(e.getValue())
                        || WmsConstants.SYNC_STATUS_NOT_REQUIRED.equals(e.getValue()))
                .map(java.util.Map.Entry::getValue).findFirst().orElse(null);
        assertEquals(WmsConstants.SYNC_STATUS_NOT_REQUIRED, syncValue,
                "工序汇报来源转人工完成后同样不能进回写队列（方案 B）");
    }
}
