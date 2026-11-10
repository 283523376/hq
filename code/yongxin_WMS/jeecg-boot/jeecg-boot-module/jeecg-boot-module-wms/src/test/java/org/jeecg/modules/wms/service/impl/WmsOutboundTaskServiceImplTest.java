package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsRmsGateway;
import org.jeecg.modules.wms.sync.IWmsWcsGateway;
import org.jeecg.modules.wms.sync.WmsRmsOutboundTask;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库下发：半成品逐库位、成品按容器聚合、设备故障转人工、RMS 失败落库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundTaskServiceImplTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsDeviceMapper deviceMapper;
    private IWmsWcsGateway wcsGateway;
    private IWmsRmsGateway rmsGateway;
    private ISysBaseAPI sysBaseAPI;
    private WmsOutboundTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        deviceMapper = mock(WmsDeviceMapper.class);
        wcsGateway = mock(IWmsWcsGateway.class);
        rmsGateway = mock(IWmsRmsGateway.class);
        sysBaseAPI = mock(ISysBaseAPI.class);

        service = new WmsOutboundTaskServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "warehouseMapper", warehouseMapper);
        WmsTestSupport.inject(service, "deviceMapper", deviceMapper);
        WmsTestSupport.inject(service, "wcsGateway", wcsGateway);
        WmsTestSupport.inject(service, "rmsGateway", rmsGateway);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
        WmsTestSupport.inject(service, "outboundStations", "OUT_01,OUT_02");
        WmsTestSupport.inject(service, "outboundPriority", 1);

        when(orderMapper.update(any(), any())).thenReturn(1);
        when(allocationMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectList(any())).thenReturn(List.of());
        when(wcsGateway.dispatchOutbound(any(), any())).thenReturn(WmsTaskDispatchResult.ok());
        when(rmsGateway.dispatchOutbound(any())).thenReturn(WmsTaskDispatchResult.ok());
    }

    private WmsOutboundOrder order(String type, String status) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("OUT1").setOutboundType(type)
                .setStatus(status);
        order.setId("o1");
        return order;
    }

    private WmsOutboundAllocation allocation(String id, String taskNo, String locationId, String container) {
        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("o1").setTaskNo(taskNo).setLocationId(locationId)
                .setContainerCode(container).setTaskStatus(WmsConstants.TASK_STATUS_NOT_DISPATCHED);
        allocation.setId(id);
        return allocation;
    }

    private void stubLocations(String... ids) {
        for (String id : ids) {
            WmsLocation location = new WmsLocation().setLocationCode("L-" + id);
            location.setId(id);
            location.setWarehouseId("wh1");
            when(locationMapper.selectById(id)).thenReturn(location);
        }
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseType(WmsConstants.WAREHOUSE_TYPE_AGILE);
        warehouse.setId("wh1");
        when(warehouseMapper.selectOne(any())).thenReturn(warehouse);
    }

    @Test
    void pick_dispatchesOneTaskPerAllocation() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_TYPE_PICK,
                WmsConstants.OUTBOUND_STATUS_ALLOCATED));
        when(allocationMapper.selectList(any())).thenReturn(List.of(
                allocation("a1", "WCS1", "l1", ""),
                allocation("a2", "WCS2", "l2", "")));
        stubLocations("l1", "l2");

        service.dispatch("o1");

        verify(wcsGateway, times(2)).dispatchOutbound(any(WmsOutboundOrder.class), any());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXECUTING, captor.getValue().getStatus());
        verify(allocationMapper, times(2)).update(any(), any());
    }

    @Test
    void sales_groupsByContainer() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_TYPE_SALES,
                WmsConstants.OUTBOUND_STATUS_ALLOCATED));
        when(allocationMapper.selectList(any())).thenReturn(List.of(
                allocation("a1", "RMS1", "l1", "CT1"),
                allocation("a2", "RMS2", "l1", "CT1")));
        stubLocations("l1");

        service.dispatch("o1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WmsRmsOutboundTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(rmsGateway).dispatchOutbound(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("RMS1", captor.getValue().get(0).getTaskNo());
        assertEquals("CT1", captor.getValue().get(0).getContainerCode());
        assertEquals(List.of("OUT_01", "OUT_02"), captor.getValue().get(0).getStationCodes());
        // 1 次统一同容器任务号 + 2 次置已受理
        verify(allocationMapper, times(3)).update(any(), any());
    }

    @Test
    void faultDevice_marksExceptionAndAlerts() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_TYPE_PICK,
                WmsConstants.OUTBOUND_STATUS_ALLOCATED));
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation("a1", "WCS1", "l1", "")));
        stubLocations("l1");
        WmsDevice fault = new WmsDevice().setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_FAULT);
        fault.setWarehouseId("wh1");
        when(deviceMapper.selectList(any())).thenReturn(List.of(fault));

        service.dispatch("o1");

        verify(wcsGateway, never()).dispatchOutbound(any(), any());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION, captor.getValue().getStatus());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE, message.getValue().getTitle());
        assertTrue(message.getValue().getContent().contains("DDJ01"));
    }

    @Test
    void rmsFailure_marksAllocationsFailedAndOrderException() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_TYPE_SALES,
                WmsConstants.OUTBOUND_STATUS_ALLOCATED));
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation("a1", "RMS1", "l1", "CT1")));
        stubLocations("l1");
        when(rmsGateway.dispatchOutbound(any()))
                .thenReturn(WmsTaskDispatchResult.fail(300008, "容器已被其他出库任务锁定", false));

        service.dispatch("o1");

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION, captor.getValue().getStatus());
        verify(allocationMapper).update(any(), any());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE, message.getValue().getTitle());
    }

    @Test
    void pick_locationMissing_marksLocationException() {
        when(orderMapper.selectById("o1")).thenReturn(order(WmsConstants.OUTBOUND_TYPE_PICK,
                WmsConstants.OUTBOUND_STATUS_ALLOCATED));
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation("a1", "WCS1", "l404", "")));
        when(locationMapper.selectById("l404")).thenReturn(null);

        service.dispatch("o1");

        verify(wcsGateway, never()).dispatchOutbound(any(), any());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_EXCEPTION, captor.getValue().getStatus());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_LOCATION_ALERT_TITLE, message.getValue().getTitle());
    }
}
