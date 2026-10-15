package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceServiceImplTest {

    private static WmsDevice device(String id, String code, String status, String warehouseId) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(status).setWarehouseId(warehouseId);
        d.setId(id);
        return d;
    }

    private static WmsWarehouse warehouse() {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode("LB").setWarehouseName("半成品立库")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO).setStatus(WmsConstants.STATUS_ENABLE);
        w.setId("W1");
        return w;
    }

    private static WmsDeviceServiceImpl newService(IWmsDeviceAlertService alertService) {
        WmsDeviceServiceImpl service = spy(new WmsDeviceServiceImpl());
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.requireEnabledWarehouse(any())).thenReturn(warehouse());
        WmsTestSupport.inject(service, "wmsWarehouseService", warehouseService);
        WmsTestSupport.inject(service, "wmsDeviceAlertService", alertService);
        return service;
    }

    // ------------------------------------------------------------ CRUD 与心跳打点

    @Test
    void addDevice_shouldRejectDuplicateCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addDevice(device(null, "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, null)));
        assertEquals("设备编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    @Test
    void addDevice_shouldDefaultStatusToOffline() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addDevice(device(null, "DDJ01", null, null));

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).save(captor.capture());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getLastHeartbeat(), "离线设备不应有心跳时间");
    }

    /**
     * 显式置在线**必须同时打心跳**：否则扫描任务下一轮会把这个刚建的在线设备判成离线
     * （它 last_heartbeat 非空，会被心跳阈值筛中）。
     */
    @Test
    void addDevice_shouldStampHeartbeatWhenOnline() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addDevice(device(null, "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, null));

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).save(captor.capture());
        assertNotNull(captor.getValue().getLastHeartbeat());
    }

    // ------------------------------------------------------------ 绑定（蓝图步骤 5 + 异常「设备绑定失败：保存状态并告警」）

    @Test
    void bind_shouldSetWarehouseOnEachDevice() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, null)))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(1, result.getBound());
        assertTrue(result.getMovedDevices().isEmpty());
        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertEquals("W1", captor.getValue().getWarehouseId());
    }

    /**
     * **蓝图的异常分支**：设备绑定失败 → 保存设备绑定状态，给出告警。
     * 语义要点是「绑定本身成功」，告警只是并行的副作用，不得阻断、不得回滚。
     */
    @Test
    void bind_shouldSucceedButWarnWhenDeviceOffline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean()))
                .thenReturn(Collections.singletonList("DDJ01"));
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, null)))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(1, result.getBound(), "绑定本身必须成功");
        assertEquals(Collections.singletonList("DDJ01"), result.getOfflineDevices());
        assertNotNull(result.getWarning());
        assertTrue(result.getWarning().contains("DDJ01"));
        // 绑定是人的即时操作 → 不节流（否则会被上一次的自动告警压掉）
        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void bind_shouldReportMovedDevices() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenReturn(Collections.emptyList());
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(Arrays.asList(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W-OLD")))
                .when(service).listByIds(anyList());
        doReturn(true).when(service).updateById(any());

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.singletonList("D1"));
        WmsDeviceBindResultVO result = service.bind(dto);

        assertEquals(Collections.singletonList("DDJ01"), result.getMovedDevices(), "搬迁必须被显式报出，不能静默");
    }

    @Test
    void bind_shouldRejectEmptyDeviceSelection() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Collections.emptyList());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.bind(dto));
        assertEquals("请至少选择一台设备", ex.getMessage());
    }

    // ------------------------------------------------------------ 解绑：必须能真的写进 NULL

    /**
     * `updateById` 默认忽略 null 字段。若用 updateById 解绑，接口会返回成功而
     * {@code warehouse_id} 纹丝不动 —— 典型的「看起来已解绑」的静默故障。
     */
    @Test
    void unbind_shouldWriteNullThroughUpdateWrapper() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(true).when(service).update(any(), any(Wrapper.class));

        service.unbind(Collections.singletonList("D1"));

        verify(service, times(1)).update(any(), any(Wrapper.class));
        verify(service, never()).updateById(any());
    }

    @Test
    void unbind_shouldRejectEmptySelection() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        assertThrows(RuntimeException.class, () -> service.unbind(Collections.emptyList()));
    }

    // ------------------------------------------------------------ 状态上报

    /** Review Focus #4：设备不存在必须给出明确的业务错误，而不是后续的 NPE。 */
    @Test
    void reportStatus_shouldRejectUnknownDeviceCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(null).when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("NOPE").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.reportStatus(dto));
        assertEquals("设备不存在：NOPE", ex.getMessage());
    }

    @Test
    void reportStatus_shouldRejectBlankCode() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        assertThrows(RuntimeException.class, () -> service.reportStatus(dto));
    }

    @Test
    void reportStatus_shouldRefreshHeartbeatOnOnline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_OFFLINE, "W1"))
                .when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));
        doReturn(true).when(service).updateById(any());

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        service.reportStatus(dto);

        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastHeartbeat(), "在线上报必须打心跳");
        verify(alertService).clearThrottle("D1");
        verify(alertService, never()).alertOffline(anyList(), anyBoolean());
    }

    @Test
    void reportStatus_shouldAlertWithThrottleWhenOffline() {
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenReturn(Collections.singletonList("DDJ01"));
        WmsDeviceServiceImpl service = newService(alertService);
        doReturn(device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W1"))
                .when(service).getOne(any(Wrapper.class), org.mockito.ArgumentMatchers.eq(false));
        doReturn(true).when(service).updateById(any());

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        service.reportStatus(dto);

        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(true));
        verify(alertService, never()).clearThrottle(any());
    }

    // ------------------------------------------------------------ 离线扫描

    @Test
    void scanOfflineDevices_shouldFlipAndReturnTransitions() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDevice stale = device("D1", "DDJ01", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        stale.setLastHeartbeat(new Date(System.currentTimeMillis() - 20 * 60_000L));
        doReturn(Collections.singletonList(stale)).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        List<WmsDevice> turned = service.scanOfflineDevices();

        assertEquals(1, turned.size());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, turned.get(0).getStatus());
        ArgumentCaptor<WmsDevice> captor = ArgumentCaptor.forClass(WmsDevice.class);
        verify(service).updateById(captor.capture());
        assertEquals(WmsConstants.DEVICE_STATUS_OFFLINE, captor.getValue().getStatus());
    }

    /**
     * **`last_heartbeat IS NULL` 的设备必须被 SQL 排除**。
     *
     * <p>这类设备从未上线过，判离线是误报；更要命的是新建设备会在 10 分钟后集体变离线，
     * 验收演示时会看到一屏假告警，把真实告警淹没。这条 WHERE 条件不是可选项。
     *
     * <p>这里直接断言 SQL 片段 —— 该行为全部由查询条件承担，用 mock 的返回列表是测不出来的。
     */
    @Test
    void scanOfflineDevices_queryMustExcludeNeverOnlineDevices() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        service.scanOfflineDevices();

        ArgumentCaptor<Wrapper<WmsDevice>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).list(captor.capture());
        String sql = ((QueryWrapper<WmsDevice>) captor.getValue()).getSqlSegment();
        assertTrue(sql.contains("last_heartbeat IS NOT NULL"),
                "必须排除从未上报心跳的设备，实际 SQL：" + sql);
        assertTrue(sql.contains("last_heartbeat <"), "必须按心跳阈值筛，实际 SQL：" + sql);
        assertTrue(sql.contains("status <>") || sql.contains("status !="),
                "只捕捉跃迁（不含已是离线的），实际 SQL：" + sql);
    }

    @Test
    void scanOfflineDevices_shouldContinueAfterSingleFailure() {
        WmsDeviceServiceImpl service = newService(mock(IWmsDeviceAlertService.class));
        WmsDevice a = device("D1", "A", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        a.setLastHeartbeat(new Date());
        WmsDevice b = device("D2", "B", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        b.setLastHeartbeat(new Date());
        WmsDevice c = device("D3", "C", WmsConstants.DEVICE_STATUS_ONLINE, "W1");
        c.setLastHeartbeat(new Date());
        doReturn(Arrays.asList(a, b, c)).when(service).list(any(Wrapper.class));
        // 实现传给 updateById 的是**新建的 update 实体**（不是候选列表里那个对象），
        // 故按实例打桩永远匹配不上；这里按调用顺序打桩：第 2 条抛、第 1/3 条成功。
        doReturn(true)
                .doThrow(new RuntimeException("锁等待超时"))
                .doReturn(true)
                .when(service).updateById(any(WmsDevice.class));

        List<WmsDevice> turned = service.scanOfflineDevices();

        assertEquals(Arrays.asList("A", "C"),
                Arrays.asList(turned.get(0).getDeviceCode(), turned.get(1).getDeviceCode()),
                "第 2 条失败不得中断整轮");
        assertEquals(2, turned.size());
    }
}