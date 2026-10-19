package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.sync.IWmsRmsGateway;
import org.jeecg.modules.wms.sync.IWmsWcsGateway;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库任务下发单测：分流网关、状态守卫、条件回写、异步下发。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionTaskServiceImplTest {

    private WmsProductionInboundMapper inboundMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsLocationMapper locationMapper;
    private WmsDeviceMapper deviceMapper;
    private IWmsRmsGateway rmsGateway;
    private IWmsWcsGateway wcsGateway;
    private ISysBaseAPI sysBaseAPI;
    private WmsProductionTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundMapper = mock(WmsProductionInboundMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        deviceMapper = mock(WmsDeviceMapper.class);
        rmsGateway = mock(IWmsRmsGateway.class);
        wcsGateway = mock(IWmsWcsGateway.class);
        sysBaseAPI = mock(ISysBaseAPI.class);

        service = new WmsProductionTaskServiceImpl(inboundMapper, warehouseMapper, locationMapper,
                deviceMapper, rmsGateway, wcsGateway, sysBaseAPI);
    }

    private WmsProductionInbound record(String productType, String status, String taskStatus) {
        WmsProductionInbound r = new WmsProductionInbound();
        r.setId("id1");
        r.setProductType(productType);
        r.setStatus(status);
        r.setTaskStatus(taskStatus);
        r.setWcsTaskNo("T1");
        r.setCarrierCode("C1");
        r.setMaterialCode("M1");
        r.setMaterialName("物料一");
        r.setBatchNo("B1");
        r.setQuantity(new BigDecimal("5"));
        r.setIdentType("成品".equals(productType) ? "二维码" : "RFID");
        if (!"成品".equals(productType)) {
            r.setLocationId("loc1");
        }
        return r;
    }

    private void stubWarehouseAndDevices(List<WmsDevice> devices) {
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId("wh1");
        when(warehouseMapper.selectOne(ArgumentMatchers.<Wrapper<WmsWarehouse>>any())).thenReturn(warehouse);
        when(deviceMapper.selectList(ArgumentMatchers.<Wrapper<WmsDevice>>any())).thenReturn(devices);
        when(inboundMapper.update(any(), any())).thenReturn(1);
    }

    private UpdateWrapper<WmsProductionInbound> lastUpdateWrapper() {
        org.mockito.ArgumentCaptor<Wrapper<WmsProductionInbound>> captor =
                org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(inboundMapper).update(ArgumentMatchers.isNull(), captor.capture());
        return (UpdateWrapper<WmsProductionInbound>) captor.getValue();
    }

    @Test
    void dispatch_finishedProduct_usesRmsAndCarrierCode() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "待上架", "未下发"));
        stubWarehouseAndDevices(List.of());
        when(rmsGateway.dispatchInbound("T1", "C1")).thenReturn(WmsTaskDispatchResult.ok());

        service.dispatchInbound("id1");

        verify(rmsGateway).dispatchInbound("T1", "C1");
        UpdateWrapper<WmsProductionInbound> w = lastUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("已受理"));
        assertTrue(w.getSqlSegment().contains("status"));
        assertTrue(w.getSqlSegment().contains("task_status"));
    }

    @Test
    void dispatch_fromExceptionAndFailed_redispatches() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "异常待人工", "失败"));
        stubWarehouseAndDevices(List.of());
        when(rmsGateway.dispatchInbound("T1", "C1")).thenReturn(WmsTaskDispatchResult.ok());

        service.dispatchInbound("id1");

        verify(rmsGateway).dispatchInbound("T1", "C1");
        assertTrue(lastUpdateWrapper().getParamNameValuePairs().containsValue("已受理"));
    }

    @Test
    void dispatch_invalidState_throws() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "已入库", "已完成"));
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.dispatchInbound("id1"));
        assertEquals("当前状态不允许重新下发", e.getMessage());
        verify(rmsGateway, never()).dispatchInbound(any(), any());
    }

    @Test
    void dispatch_deviceFault_movesToManualWithAlert() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "待上架", "未下发"));
        WmsDevice fault = new WmsDevice();
        fault.setDeviceCode("ST-01");
        fault.setStatus("故障");
        stubWarehouseAndDevices(List.of(fault));

        service.dispatchInbound("id1");

        verify(sysBaseAPI).sendSysAnnouncement(any());
        UpdateWrapper<WmsProductionInbound> w = lastUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("异常待人工"));
        assertTrue(w.getParamNameValuePairs().values().stream()
                .anyMatch(v -> String.valueOf(v).contains("ST-01")));
    }

    @Test
    void dispatch_markManualWhenRecordClosed_noAlert() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "异常待人工", "失败"));
        WmsDevice fault = new WmsDevice();
        fault.setDeviceCode("ST-01");
        fault.setStatus("故障");
        stubWarehouseAndDevices(List.of(fault));
        when(inboundMapper.update(any(), any())).thenReturn(0);

        service.dispatchInbound("id1");

        verify(sysBaseAPI, never()).sendSysAnnouncement(any());
    }

    @Test
    void dispatch_rms300001_treatedAsAccepted() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "待上架", "未下发"));
        stubWarehouseAndDevices(List.of());
        when(rmsGateway.dispatchInbound("T1", "C1"))
                .thenReturn(WmsTaskDispatchResult.fail(300001, "任务号已存在", false));

        service.dispatchInbound("id1");

        assertTrue(lastUpdateWrapper().getParamNameValuePairs().containsValue("已受理"));
    }

    @Test
    void dispatch_rms300006_goesManual() {
        when(inboundMapper.selectById("id1")).thenReturn(record("成品", "待上架", "未下发"));
        stubWarehouseAndDevices(List.of());
        when(rmsGateway.dispatchInbound("T1", "C1"))
                .thenReturn(WmsTaskDispatchResult.fail(300006, "容器编码已存在", false));

        service.dispatchInbound("id1");

        UpdateWrapper<WmsProductionInbound> w = lastUpdateWrapper();
        assertTrue(w.getParamNameValuePairs().containsValue("异常待人工"));
        assertTrue(w.getParamNameValuePairs().values().stream()
                .anyMatch(v -> String.valueOf(v).contains("300006")));
    }

    @Test
    void dispatch_semiFinished_usesWcsAndLocationCode() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "待上架", "未下发"));
        stubWarehouseAndDevices(List.of());
        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("LB-R01-C01-L01");
        when(locationMapper.selectById("loc1")).thenReturn(location);
        when(wcsGateway.dispatchInbound(any(WmsProductionInbound.class), any()))
                .thenReturn(WmsTaskDispatchResult.ok());

        service.dispatchInbound("id1");

        verify(wcsGateway).dispatchInbound(any(WmsProductionInbound.class),
                ArgumentMatchers.eq("LB-R01-C01-L01"));
    }

    @Test
    void cancel_failure_persistsTaskError() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "异常待人工", "失败"));
        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("LB-1");
        when(locationMapper.selectById("loc1")).thenReturn(location);
        org.mockito.Mockito.doThrow(new RuntimeException("WCS 超时"))
                .when(wcsGateway).cancelInbound(any(WmsProductionInbound.class), any());
        when(inboundMapper.update(any(), any())).thenReturn(1);

        service.cancelInbound("id1");

        verify(inboundMapper).update(any(), ArgumentMatchers.<Wrapper<WmsProductionInbound>>argThat(
                w -> ((UpdateWrapper<?>) w).getParamNameValuePairs().values().stream()
                        .anyMatch(v -> String.valueOf(v).contains("取消失败"))));
    }

    @Test
    void cancel_wcs_passesLocationCode() {
        when(inboundMapper.selectById("id1")).thenReturn(record("半成品", "异常待人工", "失败"));
        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("LB-1");
        when(locationMapper.selectById("loc1")).thenReturn(location);

        service.cancelInbound("id1");

        verify(wcsGateway).cancelInbound(any(WmsProductionInbound.class),
                ArgumentMatchers.eq("LB-1"));
    }

    @Test
    void dispatchAsync_invokesDispatch() throws Exception {
        WmsProductionTaskServiceImpl asyncService = spy(service);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(asyncService).dispatchInbound("id1");

        asyncService.dispatchInboundAsync("id1");

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
