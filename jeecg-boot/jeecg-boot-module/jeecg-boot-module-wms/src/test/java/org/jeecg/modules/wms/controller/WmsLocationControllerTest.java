package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsLocationAttrDTO;
import org.jeecg.modules.wms.dto.WmsLocationGenDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsLocationGenResultVO;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库位控制器薄层测试（不启 Spring 上下文）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationControllerTest {

    private static WmsLocationController newController(IWmsLocationService locationService,
                                                       IWmsWarehouseService warehouseService) {
        WmsLocationController controller = new WmsLocationController();
        WmsTestSupport.inject(controller, "service", locationService);
        WmsTestSupport.inject(controller, "wmsWarehouseService", warehouseService);
        return controller;
    }

    private static WmsLocation location(String id, String warehouseId) {
        WmsLocation l = new WmsLocation().setLocationCode("LB-R01-C06-L24")
                .setWarehouseId(warehouseId).setRowNo(1).setColNo(6).setLayerNo(24)
                .setStatus(WmsConstants.LOCATION_STATUS_IDLE);
        l.setId(id);
        return l;
    }

    // ------------------------------------------------------------ Review Focus #2：IN () 保护

    /**
     * 传一个不存在的 warehouseType 时，库区 id 集合为空。
     * 若不处理，MyBatis-Plus 会拼出 {@code IN ()} → SQL 语法错误 500，
     * 而使用者期望的只是「无匹配记录」。
     */
    @Test
    void list_shouldReturnEmptyPageWhenWarehouseTypeMatchesNothing() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryIdsByType("不存在的类型")).thenReturn(Collections.emptyList());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), "不存在的类型", 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        assertNotNull(result.getResult());
        assertEquals(0, result.getResult().getRecords().size());
        verify(locationService, never()).page(any(), any());
    }

    @Test
    void list_shouldFilterByWarehouseIdsOfType() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(warehouseService.queryIdsByType(WmsConstants.WAREHOUSE_TYPE_STEREO))
                .thenReturn(Arrays.asList("W1", "W2"));
        when(locationService.page(any(), any())).thenReturn(new Page<>());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), WmsConstants.WAREHOUSE_TYPE_STEREO, 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        verify(locationService).page(any(), any());
    }

    // ------------------------------------------------------------ M2：具体库区优先于类型过滤

    /** warehouseId 非空时必须跳过 warehouseType 分支——不再多拼一个冗余 IN 子句（M2）。 */
    @Test
    void list_shouldSkipTypeBranchWhenWarehouseIdPresent() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        when(locationService.page(any(), any())).thenReturn(new Page<>());
        WmsLocationController controller = newController(locationService, warehouseService);

        // 树上选中库区节点时 warehouseId 与 warehouseType 会同时传（WarehouseTree.onSelect）
        WmsLocation query = new WmsLocation().setWarehouseId("W1");

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                query, WmsConstants.WAREHOUSE_TYPE_STEREO, 1, 10, new MockHttpServletRequest());

        assertTrue(result.isSuccess());
        verify(warehouseService, never()).queryIdsByType(any());
        verify(locationService).page(any(), any());
    }

    // ------------------------------------------------------------ 库区名回填（含已删除库区）

    @Test
    void list_shouldFillWarehouseName() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        Page<WmsLocation> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(location("L1", "W1")));
        when(locationService.page(any(), any())).thenReturn(page);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseName("半成品立库");
        warehouse.setId("W1");
        when(warehouseService.listByIds(any())).thenReturn(Arrays.asList(warehouse));
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), null, 1, 10, new MockHttpServletRequest());

        assertEquals("半成品立库", result.getResult().getRecords().get(0).getWarehouseName());
    }

    /** Review Focus #3：库区被逻辑删除后 listByIds 查不到，回填必须留 null 而不是 NPE。 */
    @Test
    void list_shouldTolerateMissingWarehouse() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        IWmsWarehouseService warehouseService = mock(IWmsWarehouseService.class);
        Page<WmsLocation> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(location("L1", "W-DELETED")));
        when(locationService.page(any(), any())).thenReturn(page);
        when(warehouseService.listByIds(any())).thenReturn(Collections.emptyList());
        WmsLocationController controller = newController(locationService, warehouseService);

        Result<IPage<WmsLocation>> result = controller.queryPageList(
                new WmsLocation(), null, 1, 10, new MockHttpServletRequest());

        assertNull(result.getResult().getRecords().get(0).getWarehouseName());
    }

    // ------------------------------------------------------------ 生成（验收标准①）

    @Test
    void generate_shouldReturnCounts() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.generate(any())).thenReturn(new WmsLocationGenResultVO(144, 0));
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        WmsLocationGenDTO dto = new WmsLocationGenDTO();
        dto.setWarehouseId("W1");
        Result<WmsLocationGenResultVO> result = controller.generate(dto);

        assertTrue(result.isSuccess());
        assertEquals(144, result.getResult().getGenerated());
        assertEquals(0, result.getResult().getSkipped());
    }

    @Test
    void generate_shouldSurfaceServiceError() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.generate(any()))
                .thenThrow(new RuntimeException("单次最多生成 50000 个库位，本次 604220 个，请按层 / 列分批"));
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<WmsLocationGenResultVO> result = controller.generate(new WmsLocationGenDTO());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("50000"));
    }

    // ------------------------------------------------------------ 约束②：占用时改属性

    @Test
    void updateAttr_shouldSurfaceMaterialGuard() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("库位内有物料，不允许修改属性"))
                .when(locationService).updateAttr(any());
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        WmsLocationAttrDTO dto = new WmsLocationAttrDTO();
        dto.setId("L1");
        Result<?> result = controller.updateAttr(dto);

        assertFalse(result.isSuccess());
        assertEquals("库位内有物料，不允许修改属性", result.getMessage());
    }

    @Test
    void release_shouldSurfaceGuard() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("仅占用状态的库位可释放"))
                .when(locationService).release("L1");
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<?> result = controller.release("L1");

        assertFalse(result.isSuccess());
        assertEquals("仅占用状态的库位可释放", result.getMessage());
    }

    // ------------------------------------------------------------ 编码唯一（蓝图异常①）

    @Test
    void checkCode_shouldRejectDuplicate() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.checkCodeExists("LB-R01-C06-L24", null)).thenReturn(true);
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        Result<Boolean> result = controller.checkCode("LB-R01-C06-L24", null);

        assertFalse(result.isSuccess());
        assertEquals("库位编码已存在，请重新输入", result.getMessage());
    }

    @Test
    void checkCode_shouldPassWhenUnique() {
        IWmsLocationService locationService = mock(IWmsLocationService.class);
        when(locationService.checkCodeExists(any(), any())).thenReturn(false);
        WmsLocationController controller = newController(locationService, mock(IWmsWarehouseService.class));

        assertTrue(controller.checkCode("LB-R01-C06-L25", null).isSuccess());
    }
}