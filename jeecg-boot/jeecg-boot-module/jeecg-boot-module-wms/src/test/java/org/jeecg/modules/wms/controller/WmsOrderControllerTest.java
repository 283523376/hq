package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @Description: 订单接口测试（直接调用控制器方法，不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderControllerTest {

    private static WmsOrderController controller(IWmsOrderService service) {
        WmsOrderController controller = new WmsOrderController();
        WmsTestSupport.inject(controller, "service", service);
        return controller;
    }

    private static WmsOrder order(String id, String no, String type, String status) {
        WmsOrder order = new WmsOrder().setOrderNo(no).setOrderType(type).setOrderStatus(status);
        order.setId(id);
        return order;
    }

    // ------------------------------------------------------------ 分页列表（BD002-T18/T19）

    /** 列表必须回填供应商信息，且返回 200。 */
    @Test
    void queryPageList_shouldReturnPageAndFillSupplierInfo() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        Page<WmsOrder> page = new Page<>(1, 10);
        List<WmsOrder> records = Collections.singletonList(
                order("1", "PO001", WmsConstants.ORDER_TYPE_PURCHASE, WmsConstants.ORDER_STATUS_PENDING_AUDIT));
        page.setRecords(records);
        page.setTotal(1);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        Result<IPage<WmsOrder>> result = controller(service)
                .queryPageList(new WmsOrder(), 1, 10, new MockHttpServletRequest());

        assertEquals(200, result.getCode());
        assertEquals(1, result.getResult().getTotal());
        verify(service).fillSupplierInfo(records);
    }

    /** 按订单类型 + 订单状态筛选必须经 QueryGenerator 生效（BD002-T18/T19）。 */
    @Test
    void queryPageList_shouldAcceptTypeAndStatusFilters() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        Page<WmsOrder> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        // 前端把筛选值作为查询参数发给接口，Spring 绑定进控制器的实体入参；
        // QueryGenerator 只从**实体字段值**构造条件（request 参数仅用于 _begin/_end 区间与排序），
        // 故这里必须把筛选值设在实体上，只塞 request 参数是构造不出条件的。
        WmsOrder query = new WmsOrder()
                .setOrderType(WmsConstants.ORDER_TYPE_OUTSOURCE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED);

        Result<IPage<WmsOrder>> result = controller(service)
                .queryPageList(query, 1, 10, new MockHttpServletRequest());

        assertEquals(200, result.getCode());

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).page(any(Page.class), captor.capture());
        QueryWrapper<WmsOrder> wrapper = (QueryWrapper<WmsOrder>) captor.getValue();
        // 先渲染 SQL 片段，否则参数集合恒为空、「必须包含」的断言会误报失败
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("order_type"), "订单类型筛选未生效，实际：" + sqlSegment);
        assertTrue(sqlSegment.contains("order_status"), "订单状态筛选未生效，实际：" + sqlSegment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(WmsConstants.ORDER_TYPE_OUTSOURCE),
                "订单类型筛选未生效，实际参数：" + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getParamNameValuePairs().containsValue(WmsConstants.ORDER_STATUS_AUDITED),
                "订单状态筛选未生效，实际参数：" + wrapper.getParamNameValuePairs());
    }

    /**
     * 非持久化字段（supplierName / supplierCode）**绝不能**进入 SQL 条件。
     *
     * <p>`wms_order` 表没有这两列；BD-002 修复期曾因 `supplierCode` 被 QueryGenerator 拼成条件
     * 导致订单列表 SQL 报错，控制器由此改为显式置空。本用例守住这一点。
     */
    @Test
    void queryPageList_shouldIgnoreNonPersistentFields() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        Page<WmsOrder> page = new Page<>(1, 10);
        page.setRecords(Collections.emptyList());
        page.setTotal(0);
        doReturn(page).when(service).page(any(Page.class), any(Wrapper.class));

        WmsOrder probe = new WmsOrder().setSupplierName("永鑫五金").setSupplierCode("SUP001");

        Result<IPage<WmsOrder>> result = controller(service)
                .queryPageList(probe, 1, 10, new MockHttpServletRequest());

        assertEquals(200, result.getCode());
        // 直接断言控制器的动作：它必须把非持久化字段置空后才交给 QueryGenerator
        assertNull(probe.getSupplierName(), "控制器必须先置空 supplierName 再构造查询条件");
        assertNull(probe.getSupplierCode(), "控制器必须先置空 supplierCode 再构造查询条件");

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).page(any(Page.class), captor.capture());
        QueryWrapper<WmsOrder> wrapper = (QueryWrapper<WmsOrder>) captor.getValue();
        // 先渲染 SQL 片段，否则空参数集合上的 assertFalse 是永真断言
        String sqlSegment = wrapper.getSqlSegment();
        assertFalse(sqlSegment.contains("supplier_name"),
                "非持久化字段 supplierName 不得被拼进查询条件，实际：" + sqlSegment);
        assertFalse(sqlSegment.contains("supplier_code"),
                "非持久化字段 supplierCode 不得被拼进查询条件，实际：" + sqlSegment);
        assertFalse(wrapper.getParamNameValuePairs().containsValue("永鑫五金"),
                "非持久化字段 supplierName 的值不得出现在 SQL 参数中");
        assertFalse(wrapper.getParamNameValuePairs().containsValue("SUP001"),
                "非持久化字段 supplierCode 的值不得出现在 SQL 参数中");
    }

    // ------------------------------------------------------------ 通过id查询

    /** 查不到时返回「未找到对应实体」。 */
    @Test
    void queryById_shouldReturnErrorWhenMissing() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(null).when(service).getById("404");

        Result<WmsOrder> result = controller(service).queryById("404");

        assertEquals(false, result.isSuccess());
        assertEquals("未找到对应实体", result.getMessage());
    }

    /** 查到实体时回填供应商信息并返回 200。 */
    @Test
    void queryById_shouldReturnOkWhenFound() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(order("1", "PO001", WmsConstants.ORDER_TYPE_PURCHASE,
                WmsConstants.ORDER_STATUS_PENDING_AUDIT)).when(service).getById("1");

        Result<WmsOrder> result = controller(service).queryById("1");

        assertEquals(200, result.getCode());
        assertEquals("PO001", result.getResult().getOrderNo());
        verify(service).fillSupplierInfo(any());
    }

    // ------------------------------------------------------------ 订单号唯一性校验

    /** 空订单号必须被挡下。 */
    @Test
    void checkOrderNo_shouldRejectBlankCode() {
        IWmsOrderService service = mock(IWmsOrderService.class);

        Result<Boolean> result = controller(service).checkOrderNo("", null);

        assertEquals(false, result.isSuccess());
        assertEquals("订单号不能为空", result.getMessage());
    }

    /** 订单号已占用时给出提示。 */
    @Test
    void checkOrderNo_shouldRejectExistingOrderNo() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(true).when(service).checkOrderNoExists("PO001", null);

        Result<Boolean> result = controller(service).checkOrderNo("PO001", null);

        assertEquals("订单号已存在，请重新输入", result.getMessage());
    }

    /** 订单号可用时返回 200。 */
    @Test
    void checkOrderNo_shouldReturnOkWhenFree() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(false).when(service).checkOrderNoExists("PO001", null);

        Result<Boolean> result = controller(service).checkOrderNo("PO001", null);

        assertEquals(200, result.getCode());
        assertEquals(true, result.getResult());
    }

    // ------------------------------------------------------------ 增改

    /** 新增成功（BD002-T08）。 */
    @Test
    void add_shouldReturnOk() {
        IWmsOrderService service = mock(IWmsOrderService.class);

        Result<WmsOrder> result = controller(service).add(order(null, "PO001",
                WmsConstants.ORDER_TYPE_PURCHASE, null));

        assertEquals(200, result.getCode());
        assertEquals("添加成功！", result.getMessage());
        verify(service).addOrder(any());
    }

    /** 订单号重复时消息原样透出（BD002-T11）。 */
    @Test
    void add_shouldReturnErrorOnDuplicateOrderNo() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("订单号已存在，请重新输入")).when(service).addOrder(any());

        Result<WmsOrder> result = controller(service).add(order(null, "PO001",
                WmsConstants.ORDER_TYPE_PURCHASE, null));

        assertEquals(false, result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals("订单号已存在，请重新输入", result.getMessage());
    }

    /** 编辑成功。 */
    @Test
    void edit_shouldReturnOk() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        WmsOrder input = order("1", "PO001", WmsConstants.ORDER_TYPE_PURCHASE,
                WmsConstants.ORDER_STATUS_PENDING_AUDIT);

        Result<WmsOrder> result = controller(service).edit(input);

        assertEquals(200, result.getCode());
        assertEquals("修改成功！", result.getMessage());
        verify(service).editOrder(input);
    }

    /** 编辑非法状态的订单时消息原样透出。 */
    @Test
    void edit_shouldReturnErrorOnNonEditableState() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("仅待审核或已驳回状态的订单可编辑")).when(service).editOrder(any());

        Result<WmsOrder> result = controller(service).edit(order("1", "PO001",
                WmsConstants.ORDER_TYPE_PURCHASE, WmsConstants.ORDER_STATUS_DISPATCHED));

        assertEquals(false, result.isSuccess());
        assertEquals("仅待审核或已驳回状态的订单可编辑", result.getMessage());
    }

    // ------------------------------------------------------------ 审核（BD002-T14/T15/T16）

    /** 审核通过返回「审核通过！」。 */
    @Test
    void audit_shouldReturnAuditPassedMessage() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(true);

        Result<?> result = controller(service).audit(dto);

        assertEquals(200, result.getCode());
        assertEquals("审核通过！", result.getMessage());
        verify(service).auditOrder(dto);
    }

    /** 驳回返回「已驳回！」。 */
    @Test
    void audit_shouldReturnRejectedMessage() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(false);
        dto.setAuditRemark("价格不符合预算");

        Result<?> result = controller(service).audit(dto);

        assertEquals(200, result.getCode());
        assertEquals("已驳回！", result.getMessage());
        verify(service).auditOrder(dto);
    }

    /** 重复审核时守卫消息原样透出（BD002-T16）。 */
    @Test
    void audit_shouldReturnErrorOnRepeatedAudit() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("仅待审核状态的订单可审核")).when(service).auditOrder(any());
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(true);

        Result<?> result = controller(service).audit(dto);

        assertEquals(false, result.isSuccess());
        assertEquals("仅待审核状态的订单可审核", result.getMessage());
    }

    // ------------------------------------------------------------ 下达 / 重新同步（BD002-T23/T24）

    /**
     * 下达接口返回 200 且带同步结果——**「下达成功」与「同步成功」是两件事**。
     *
     * <p>同步失败时 `synced=false`，前端据此给黄色警告而非错误提示。这里刻意用同步失败的场景，
     * 断言「下达接口仍然 200」，防止将来有人把同步失败也改成 HTTP 500。
     */
    @Test
    void dispatch_shouldReturnOkWithSyncOutcome() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        WmsOrderSyncOutcomeVO vo = new WmsOrderSyncOutcomeVO()
                .setSynced(false).setSyncError("ERP 未配置").setRetryCount(3);
        doReturn(vo).when(service).dispatchOrder("1");

        Result<WmsOrderSyncOutcomeVO> result = controller(service).dispatch("1");

        assertEquals(200, result.getCode());
        assertEquals("下达成功！", result.getMessage());
        assertFalse(result.getResult().getSynced());
        assertEquals("ERP 未配置", result.getResult().getSyncError());
        assertEquals(3, result.getResult().getRetryCount());
    }

    /** 下达前置状态不合法时消息原样透出。 */
    @Test
    void dispatch_shouldReturnErrorOnNonAudited() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("仅已审核状态的订单可下达")).when(service).dispatchOrder("1");

        Result<WmsOrderSyncOutcomeVO> result = controller(service).dispatch("1");

        assertEquals(false, result.isSuccess());
        assertEquals("仅已审核状态的订单可下达", result.getMessage());
    }

    /** 未抢到认领时 synced=null，接口仍应 200（前端据 null 提示「本次跳过」）。 */
    @Test
    void dispatch_shouldReturnNullSyncedWhenClaimLost() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(new WmsOrderSyncOutcomeVO().setSynced(null)).when(service).dispatchOrder("1");

        Result<WmsOrderSyncOutcomeVO> result = controller(service).dispatch("1");

        assertEquals(200, result.getCode());
        assertNull(result.getResult().getSynced());
    }

    /** 重新同步返回「已重新同步！」与结果体。 */
    @Test
    void resync_shouldReturnOkWithOutcome() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doReturn(new WmsOrderSyncOutcomeVO().setSynced(true).setRetryCount(0))
                .when(service).resyncOrder("1");

        Result<WmsOrderSyncOutcomeVO> result = controller(service).resync("1");

        assertEquals(200, result.getCode());
        assertEquals("已重新同步！", result.getMessage());
        assertTrue(result.getResult().getSynced());
    }

    /** 重新同步前置状态不合法时消息原样透出。 */
    @Test
    void resync_shouldReturnErrorOnNonFailedState() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("仅同步失败或待同步的订单可重新同步")).when(service).resyncOrder("1");

        Result<WmsOrderSyncOutcomeVO> result = controller(service).resync("1");

        assertEquals(false, result.isSuccess());
        assertEquals("仅同步失败或待同步的订单可重新同步", result.getMessage());
    }

    // ------------------------------------------------------------ 删除

    /** 删除成功。 */
    @Test
    void delete_shouldReturnOk() {
        IWmsOrderService service = mock(IWmsOrderService.class);

        Result<?> result = controller(service).delete("1");

        assertEquals(200, result.getCode());
        assertEquals("删除成功！", result.getMessage());
        verify(service).removeOrder("1");
    }

    /** 已下达订单不可删时消息原样透出。 */
    @Test
    void delete_shouldReturnErrorOnDispatched() {
        IWmsOrderService service = mock(IWmsOrderService.class);
        doThrow(new RuntimeException("仅待审核或已驳回状态的订单可删除")).when(service).removeOrder("1");

        Result<?> result = controller(service).delete("1");

        assertEquals(false, result.isSuccess());
        assertEquals("仅待审核或已驳回状态的订单可删除", result.getMessage());
    }

    /** 批量删除的空 ids 必须被挡下。 */
    @Test
    void deleteBatch_shouldRejectBlankIds() {
        IWmsOrderService service = mock(IWmsOrderService.class);

        Result<?> result = controller(service).deleteBatch("");

        assertEquals(false, result.isSuccess());
        assertEquals("参数不识别！", result.getMessage());
    }

    /** 批量删除按逗号切分 ids。 */
    @Test
    void deleteBatch_shouldParseCommaSeparatedIds() {
        IWmsOrderService service = mock(IWmsOrderService.class);

        Result<?> result = controller(service).deleteBatch("1,2");

        assertEquals(200, result.getCode());
        verify(service).removeBatch(Arrays.asList("1", "2"));
    }
}