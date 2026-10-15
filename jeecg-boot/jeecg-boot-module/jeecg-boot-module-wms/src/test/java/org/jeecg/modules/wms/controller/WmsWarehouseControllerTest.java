package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * @Description: 库区控制器薄层测试
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsWarehouseControllerTest {

    private static WmsWarehouse warehouse(String id, Integer layers) {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode("LB").setWarehouseName("半成品立库")
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO)
                .setRowCount(1).setColCount(6).setLayerCount(layers)
                .setStatus(WmsConstants.STATUS_ENABLE);
        w.setId(id);
        return w;
    }

    private static WmsWarehouseController newController(IWmsWarehouseService warehouseService,
                                                        IWmsLocationService locationService) {
        WmsWarehouseController controller = new WmsWarehouseController();
        WmsTestSupport.inject(controller, "service", warehouseService);
        WmsTestSupport.inject(controller, "wmsLocationService", locationService);
        return controller;
    }

    @Test
    void treeList_shouldDelegate() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryTreeList()).thenReturn(java.util.Collections.emptyList());
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertTrue(controller.treeList().isSuccess());
    }

    /** 询详情时回填越界库位数，用于「有 N 个库位位于当前维度之外」的提示。 */
    @Test
    void queryById_shouldFillOutOfEnvelopeCount() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.getById("W1")).thenReturn(warehouse("W1", 10));
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.countOutOfEnvelope("W1")).thenReturn(84L);
        WmsWarehouseController controller = newController(warehouseService, locationService);

        Result<WmsWarehouse> result = controller.queryById("W1");

        assertTrue(result.isSuccess());
        assertEquals(84L, result.getResult().getOutOfEnvelopeCount());
    }

    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.getById("nope")).thenReturn(null);
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertFalse(controller.queryById("nope").isSuccess());
    }

    @Test
    void delete_shouldSurfaceGuard() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        doThrow(new RuntimeException("该库区下存在库位，不允许删除")).when(warehouseService).removeWarehouse("W1");
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        Result<?> result = controller.delete("W1");

        assertFalse(result.isSuccess());
        assertEquals("该库区下存在库位，不允许删除", result.getMessage());
    }

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.checkCodeExists("LB", null)).thenReturn(true);
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertFalse(controller.checkCode("LB", null).isSuccess());
    }

    /** 维度自由增减：缩到 10 层照样保存成功，控制器不得加任何额外校验。 */
    @Test
    void edit_shouldAllowShrinkingDimensions() {
        IWmsWarehouseService warehouseService = spy(new org.jeecg.modules.wms.service.impl.WmsWarehouseServiceImpl());
        doReturn(0L).when(warehouseService).count(any(Wrapper.class));
        // 编辑是两条语句：常规字段走 updateById，三个维度走 UpdateWrapper（null 也要真的写进去）
        doReturn(true).when(warehouseService).updateById(any());
        doReturn(true).when(warehouseService).update(any(), any(Wrapper.class));
        WmsWarehouseController controller = newController(warehouseService, mock(IWmsLocationService.class));

        assertTrue(controller.edit(warehouse("W1", 10)).isSuccess());
    }
}