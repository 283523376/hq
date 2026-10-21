package org.jeecg.modules.wms.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.dto.WmsOutboundCloseDTO;
import org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSalesCreateDTO;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOutboundConfirmVO;
import org.jeecg.modules.wms.vo.WmsOutboundScanVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库控制器：错误透传、创建后销售同步、确认出库同步、关单取消任务、权限注解。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundControllerTest {

    private IWmsOutboundService service;
    private IWmsOutboundSyncService syncService;
    private IWmsOutboundTaskService taskService;
    private WmsOutboundController controller;

    @BeforeEach
    void setUp() {
        service = mock(IWmsOutboundService.class);
        syncService = mock(IWmsOutboundSyncService.class);
        taskService = mock(IWmsOutboundTaskService.class);
        controller = new WmsOutboundController();
        WmsTestSupport.inject(controller, "service", service);
        WmsTestSupport.inject(controller, "syncService", syncService);
        WmsTestSupport.inject(controller, "taskService", taskService);
    }

    private WmsOutboundOrder order(String id, String no) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo(no)
                .setOutboundType(org.jeecg.modules.wms.constant.WmsConstants.OUTBOUND_TYPE_SALES);
        order.setId(id);
        return order;
    }

    @Test
    void scan_passesErrorTextThrough() {
        when(service.scanPick("FG001173")).thenThrow(new RuntimeException("工单号无效"));

        Result<WmsOutboundScanVO> result = controller.scanPick("FG001173");

        assertEquals(500, result.getCode());
        assertEquals("工单号无效", result.getMessage());
        assertNull(result.getResult());
    }

    @Test
    void createSales_triggersSaleOrderSync() {
        when(service.createSales(any(WmsOutboundSalesCreateDTO.class))).thenReturn(order("o1", "XS001"));
        when(service.getById("o1")).thenReturn(order("o1", "XS001"));
        when(syncService.syncSaleOrder("o1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        Result<WmsOutboundOrder> result = controller.createSales(new WmsOutboundSalesCreateDTO());

        assertEquals(200, result.getCode());
        assertNotNull(result.getResult());
        verify(syncService).syncSaleOrder("o1", true);
        verify(syncService).persistSaleResult(eq("o1"), any(WmsOrderSyncAttempt.class));
    }

    @Test
    void createSales_syncFailureStillCreatesOrder() {
        when(service.createSales(any(WmsOutboundSalesCreateDTO.class))).thenReturn(order("o1", "XS001"));
        when(service.getById("o1")).thenReturn(order("o1", "XS001"));
        when(syncService.syncSaleOrder("o1", true))
                .thenReturn(new WmsOrderSyncAttempt(false, "金蝶不可用", 3));

        Result<WmsOutboundOrder> result = controller.createSales(new WmsOutboundSalesCreateDTO());

        assertEquals(200, result.getCode());
        assertTrue(result.getMessage().contains("金蝶不可用"), "实际文案：" + result.getMessage());
    }

    @Test
    void confirm_returnsNeedConfirmVo() {
        WmsOutboundConfirmDTO dto = new WmsOutboundConfirmDTO();
        when(service.confirm("o1", dto))
                .thenReturn(WmsOutboundConfirmVO.needConfirm("XS001", "销售订单未同步到金蝶，确认继续出库？"));

        Result<WmsOutboundConfirmVO> result = controller.confirm("o1", dto);

        assertEquals(200, result.getCode());
        assertTrue(result.getResult().isNeedSaleOrderConfirm());
        assertFalse(result.getResult().isConfirmed());
        verify(syncService, never()).sync(any(), eq(true));
    }

    @Test
    void confirmPick_triggersSync() {
        WmsOutboundConfirmDTO dto = new WmsOutboundConfirmDTO();
        when(service.confirmPick("o1", dto)).thenReturn(WmsOutboundConfirmVO.ok("LL001"));
        when(syncService.sync("o1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        Result<WmsOutboundConfirmVO> result = controller.confirmPick("o1", dto);

        assertEquals(200, result.getCode());
        assertTrue(result.getResult().isConfirmed());
        verify(syncService).sync("o1", true);
        verify(syncService).persistResult(eq("o1"), any(WmsOrderSyncAttempt.class));
    }

    @Test
    void confirm_syncFailureKeepsConfirmedWithoutRollback() {
        WmsOutboundConfirmDTO dto = new WmsOutboundConfirmDTO();
        when(service.confirm("o1", dto)).thenReturn(WmsOutboundConfirmVO.ok("XS001"));
        when(syncService.sync("o1", true)).thenReturn(new WmsOrderSyncAttempt(false, "出库单同步失败", 3));

        Result<WmsOutboundConfirmVO> result = controller.confirm("o1", dto);

        assertTrue(result.getResult().isConfirmed());
        assertTrue(result.getMessage().contains("出库单同步失败"), "实际文案：" + result.getMessage());
    }

    @Test
    void syncSale_triggersSaleOrderSync() {
        when(service.getById("o1")).thenReturn(order("o1", "XS001"));
        when(syncService.syncSaleOrder("o1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        Result<WmsOrderSyncAttempt> result = controller.resyncSale("o1");

        assertEquals(200, result.getCode());
        verify(syncService).syncSaleOrder("o1", true);
        verify(syncService).persistSaleResult(eq("o1"), any(WmsOrderSyncAttempt.class));
    }

    @Test
    void syncSale_rejectsNonSalesOrder() {
        WmsOutboundOrder pick = new WmsOutboundOrder().setOutboundNo("LL001")
                .setOutboundType(org.jeecg.modules.wms.constant.WmsConstants.OUTBOUND_TYPE_PICK);
        pick.setId("o1");
        when(service.getById("o1")).thenReturn(pick);

        Result<WmsOrderSyncAttempt> result = controller.resyncSale("o1");

        assertEquals(500, result.getCode());
        assertEquals("仅销售出库单可同步销售订单", result.getMessage());
        verify(syncService, never()).syncSaleOrder(any(), eq(true));
    }

    @Test
    void confirmPick_mockedSyncSaysMocked() {
        WmsOutboundConfirmDTO dto = new WmsOutboundConfirmDTO();
        when(service.confirmPick("o1", dto)).thenReturn(WmsOutboundConfirmVO.ok("LL001"));
        when(syncService.sync("o1", true)).thenReturn(new WmsOrderSyncAttempt(true, null, 0, true));

        Result<WmsOutboundConfirmVO> result = controller.confirmPick("o1", dto);

        assertTrue(result.getMessage().contains("模拟"), "实际文案：" + result.getMessage());
    }

    @Test
    void close_callsTaskCancel() {
        WmsOutboundCloseDTO dto = new WmsOutboundCloseDTO();
        dto.setReason("客户取消");

        Result<Void> result = controller.close("o1", dto);

        assertEquals(200, result.getCode());
        verify(service).close("o1", "客户取消");
        verify(taskService).cancel("o1");
    }

    @Test
    void endpoints_haveExpectedPermissions() throws Exception {
        assertPermission("scanPick", "wms:outbound:scan");
        assertPermission("createPick", "wms:outbound:add");
        assertPermission("createSales", "wms:outbound:sales");
        assertPermission("list", "wms:outbound:list");
        assertPermission("detail", "wms:outbound:list");
        assertPermission("allocate", "wms:outbound:allocate");
        assertPermission("dispatch", "wms:outbound:dispatch");
        assertPermission("review", "wms:outbound:review");
        assertPermission("confirmPick", "wms:outbound:confirm");
        assertPermission("confirm", "wms:outbound:confirm");
        assertPermission("resync", "wms:outbound:sync");
        assertPermission("resyncSale", "wms:outbound:sync");
        assertPermission("close", "wms:outbound:close");
    }

    private void assertPermission(String methodName, String expected) {
        List<Method> matches = Arrays.stream(WmsOutboundController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
        assertEquals(1, matches.size(), "未找到唯一方法：" + methodName);
        RequiresPermissions annotation = matches.get(0).getAnnotation(RequiresPermissions.class);
        assertNotNull(annotation, methodName + " 缺少 @RequiresPermissions");
        assertTrue(Arrays.asList(annotation.value()).contains(expected),
                methodName + " 权限应为 " + expected + "，实际：" + Arrays.toString(annotation.value()));
    }
}
