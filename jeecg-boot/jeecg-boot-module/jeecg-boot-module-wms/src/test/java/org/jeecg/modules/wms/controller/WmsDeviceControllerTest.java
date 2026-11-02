package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备控制器薄层测试
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceControllerTest {

    private static WmsDevice device(String id, String code, String warehouseId) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setWarehouseId(warehouseId)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        d.setId(id);
        return d;
    }

    private static WmsDeviceController newController(IWmsDeviceService deviceService,
                                                     IWmsWarehouseService warehouseService) {
        WmsDeviceController controller = new WmsDeviceController();
        WmsTestSupport.inject(controller, "service", deviceService);
        WmsTestSupport.inject(controller, "wmsWarehouseService", warehouseService);
        return controller;
    }

    @Test
    void list_shouldFillWarehouseName() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", "W1")));
        when(deviceService.page(any(), any())).thenReturn(page);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseName("半成品立库");
        warehouse.setId("W1");
        when(warehouseService.listByIds(any())).thenReturn(Arrays.asList(warehouse));
        WmsDeviceController controller = newController(deviceService, warehouseService);

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), null, 1, 10, new MockHttpServletRequest());

        assertEquals("半成品立库", result.getResult().getRecords().get(0).getWarehouseName());
    }

    /** Review Focus #3：设备指向已逻辑删除的库区时，回填留 null，不得 NPE。 */
    @Test
    void list_shouldTolerateDeletedWarehouse() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", "W-DELETED")));
        when(deviceService.page(any(), any())).thenReturn(page);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.listByIds(any())).thenReturn(Collections.emptyList());
        WmsDeviceController controller = newController(deviceService, warehouseService);

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), null, 1, 10, new MockHttpServletRequest());

        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    @Test
    void list_shouldTolerateUnboundDevices() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        Page<WmsDevice> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(device("D1", "DDJ01", null)));
        when(deviceService.page(any(), any())).thenReturn(page);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        Result<IPage<WmsDevice>> result = controller.queryPageList(
                new WmsDevice(), null, 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    // ------------------------------------------------------------ 绑定（验收标准②：绑定成功 + 离线告警）

    @Test
    void bind_shouldReturnWarningPayload() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.bind(any())).thenReturn(new WmsDeviceBindResultVO(
                2, Collections.emptyList(), Collections.singletonList("DDJ01"),
                "已绑定到库区[半成品立库]，但以下 1 台设备当前离线，已发出告警：DDJ01"));
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceBindDTO dto = new WmsDeviceBindDTO();
        dto.setWarehouseId("W1");
        dto.setDeviceIds(Arrays.asList("D1", "D2"));
        Result<WmsDeviceBindResultVO> result = controller.bind(dto);

        assertTrue(result.isSuccess(), "绑定本身必须报成功");
        assertEquals(2, result.getResult().getBound());
        assertNotNull(result.getResult().getWarning(), "离线告警走 warning，不走 error");
    }

    @Test
    void bind_shouldSurfaceGuard() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        doThrow(new RuntimeException("库区不存在或已停用")).when(deviceService).bind(any());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertFalse(controller.bind(new WmsDeviceBindDTO()).isSuccess());
    }

    @Test
    void unbind_shouldDelegate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertTrue(controller.unbind(Arrays.asList("D1")).isSuccess());
        verify(deviceService).unbind(Arrays.asList("D1"));
    }

    // ------------------------------------------------------------ 状态上报

    @Test
    void reportStatus_shouldSurfaceUnknownDevice() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        doThrow(new RuntimeException("设备不存在：NOPE")).when(deviceService).reportStatus(any());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("NOPE").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);
        Result<?> result = controller.reportStatus(dto);

        assertFalse(result.isSuccess());
        assertEquals("设备不存在：NOPE", result.getMessage());
    }

    @Test
    void reportStatus_shouldDelegate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        WmsDeviceStatusDTO dto = new WmsDeviceStatusDTO();
        dto.setDeviceCode("DDJ01").setStatus(WmsConstants.DEVICE_STATUS_ONLINE);

        assertTrue(controller.reportStatus(dto).isSuccess());
        verify(deviceService).reportStatus(dto);
    }

    // ------------------------------------------------------------ 未绑定列表（绑定弹窗数据源）

    @Test
    void listUnbound_shouldQueryNullWarehouse() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.list(any(Wrapper.class))).thenReturn(Collections.emptyList());
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertTrue(controller.listUnbound().isSuccess());
        verify(deviceService).list(any(Wrapper.class));
    }

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.checkCodeExists("DDJ01", null)).thenReturn(true);
        WmsDeviceController controller = newController(deviceService, mock(IWmsWarehouseService.class));

        assertFalse(controller.checkCode("DDJ01", null).isSuccess());
    }
}