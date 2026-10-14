package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 订单状态机守卫单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderServiceImplTest {

    // ------------------------------------------------------------ 创建

    /** 新建订单状态必须落到「待审核」，且订单号首尾空格被去掉。 */
    @Test
    void addOrder_shouldDefaultToPendingAuditAndTrim() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("  PO001  ")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        service.addOrder(order);

        assertEquals("PO001", order.getOrderNo());
        assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, order.getOrderStatus());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertEquals(0, order.getRetryCount());
    }

    /** 订单号重复必须拒绝。 */
    @Test
    void addOrder_shouldRejectDuplicatedOrderNo() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(true).when(service).checkOrderNoExists(any(), any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addOrder(order));
        assertEquals("订单号已存在，请重新输入", ex.getMessage());
    }

    /** 订单类型必须在允许集合内。 */
    @Test
    void addOrder_shouldRejectUnknownOrderType() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(false).when(service).checkOrderNoExists(any(), any());

        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType("不存在的类型");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addOrder(order));
        assertEquals("订单类型不合法", ex.getMessage());
    }

    // ------------------------------------------------------------ 审核

    /** 待审核 → 审核通过 → 已审核，并写入审核人与时间。 */
    @Test
    void auditOrder_pass_shouldMoveToAudited() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setId("1");
        doReturn(order).when(service).getById("1");
        doReturn(true).when(service).updateById(any());

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(true);
        service.auditOrder(dto);

        assertEquals(WmsConstants.ORDER_STATUS_AUDITED, order.getOrderStatus());
        assertEquals("1", order.getId());
    }

    /** 待审核 → 驳回 → 已驳回，且驳回原因必填。 */
    @Test
    void auditOrder_reject_shouldMoveToRejected() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setId("1");
        doReturn(order).when(service).getById("1");
        doReturn(true).when(service).updateById(any());

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(false);
        dto.setAuditRemark("价格不符合预算");
        service.auditOrder(dto);

        assertEquals(WmsConstants.ORDER_STATUS_REJECTED, order.getOrderStatus());
        assertEquals("价格不符合预算", order.getAuditRemark());
    }

    /** 驳回但不填原因 → 必须拒绝。 */
    @Test
    void auditOrder_rejectWithoutRemark_shouldFail() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setId("1");
        doReturn(order).when(service).getById("1");

        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");
        dto.setPass(false);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto));
        assertEquals("驳回时必须填写驳回原因", ex.getMessage());
    }

    // ------------------------------------------------------------ 守卫：非法前置状态

    /** 只有「待审核」可审核；已审核/已下达/已驳回都必须被拒。 */
    @Test
    void auditOrder_shouldRejectNonPendingStates() {
        for (String status : new String[]{
                WmsConstants.ORDER_STATUS_AUDITED,
                WmsConstants.ORDER_STATUS_DISPATCHED,
                WmsConstants.ORDER_STATUS_REJECTED}) {
            WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
            WmsOrder order = new WmsOrder().setOrderStatus(status);
            order.setId("1");
            doReturn(order).when(service).getById("1");

            WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
            dto.setId("1");
            dto.setPass(true);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto),
                    "状态 " + status + " 不应允许审核");
            assertEquals("仅待审核状态的订单可审核", ex.getMessage());
        }
    }

    /** 只有「待审核/已驳回」可编辑；已审核/已下达必须被拒。 */
    @Test
    void editOrder_shouldRejectNonEditableStates() {
        for (String status : new String[]{
                WmsConstants.ORDER_STATUS_AUDITED,
                WmsConstants.ORDER_STATUS_DISPATCHED}) {
            WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
            WmsOrder existing = new WmsOrder().setOrderStatus(status);
            existing.setId("1");
            doReturn(existing).when(service).getById("1");

            WmsOrder input = new WmsOrder()
                    .setOrderNo("PO001")
                    .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
            input.setId("1");

            RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input),
                    "状态 " + status + " 不应允许编辑");
            assertEquals("仅待审核或已驳回状态的订单可编辑", ex.getMessage());
        }
    }

    /** 编辑「已驳回」订单后，状态回到「待审核」且清空上一轮审核痕迹。 */
    @Test
    void editOrder_rejectedShouldReturnToPendingAudit() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_REJECTED);
        existing.setId("1");
        doReturn(existing).when(service).getById("1");
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsOrder input = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
        input.setId("1");

        service.editOrder(input);

        assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, input.getOrderStatus());
        assertEquals(null, input.getAuditRemark());
    }

    /** 只有「待审核/已驳回」可删除；已下达必须被拒。 */
    @Test
    void removeOrder_shouldRejectDispatched() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        order.setId("1");
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeOrder("1"));
        assertEquals("仅待审核或已驳回状态的订单可删除", ex.getMessage());
    }

    // ------------------------------------------------------------ Review Focus #5：供应商回填容错

    /** 构造被测对象并注入 mock 的供应商 Mapper——回填查询走它，裸 new 会因字段为空 NPE。 */
    private WmsOrderServiceImpl serviceWithSupplierMapper(List<WmsSupplier> suppliers) {
        WmsOrderServiceImpl service = new WmsOrderServiceImpl();
        WmsSupplierMapper mapper = mock(WmsSupplierMapper.class);
        when(mapper.selectBatchIds(any())).thenReturn(suppliers);
        service.setSupplierMapperForTest(mapper);
        return service;
    }

    /** 供应商查不到（已删除/已停用）时，回填必须静默跳过，不能抛异常把整个列表打挂。 */
    @Test
    void fillSupplierInfo_shouldTolerateMissingSupplier() {
        // selectBatchIds 自带 del_flag=0 过滤，已删除的供应商查不到 → 返回空集合
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());
        WmsOrder order = new WmsOrder().setSupplierId("不存在的供应商ID");
        order.setId("1");

        assertDoesNotThrow(() -> service.fillSupplierInfo(Collections.singletonList(order)));
        assertEquals(null, order.getSupplierName());
    }

    /** 空列表与全空 supplierId 都必须安全返回（此时根本不该查库）。 */
    @Test
    void fillSupplierInfo_shouldHandleEmptyInput() {
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());

        assertDoesNotThrow(() -> service.fillSupplierInfo(null));
        assertDoesNotThrow(() -> service.fillSupplierInfo(Collections.emptyList()));
        WmsOrder blank = new WmsOrder();
        blank.setId("1");
        assertDoesNotThrow(() -> service.fillSupplierInfo(Collections.singletonList(blank)));
    }

    /** 供应商存在时必须正确回填名称（防止"只测了容错、没测正常路径"）。 */
    @Test
    void fillSupplierInfo_shouldFillExistingSupplier() {
        WmsSupplier supplier = new WmsSupplier().setSupplierName("永鑫五金");
        supplier.setId("S1");
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.singletonList(supplier));
        WmsOrder order = new WmsOrder().setSupplierId("S1");
        order.setId("1");

        service.fillSupplierInfo(Collections.singletonList(order));

        assertEquals("永鑫五金", order.getSupplierName());
    }

    // ------------------------------------------------------------ Review Focus #3：并发互斥

    /** 认领失败（别人已持有）时必须跳过，绝不能调用 gateway 重复投递 ERP/MES。 */
    @Test
    void dispatchOrder_whenClaimFails_shouldSkipSync() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED);
        order.setId("1");
        doReturn(order).when(service).getById("1");
        // dispatchOrder 会先写下达状态，必须打桩 updateById，否则会走到真实的 ServiceImpl（baseMapper 为空）NPE
        doReturn(true).when(service).updateById(any());
        doReturn(0).when(service).claimForSync(any(), any());

        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        service.setSyncServiceForTest(syncService);

        WmsOrderSyncOutcomeVO outcome = service.dispatchOrder("1");

        // 没抢到锁 → 既不调同步，也不谎报成功
        verify(syncService, never()).syncOrder(any(), anyBoolean());
        assertEquals(null, outcome.getSynced());
    }

    /** 下达成功且认领成功 → 必须调用同步一次，并把结果映射进 VO。 */
    @Test
    void dispatchOrder_whenClaimSucceeds_shouldSyncOnce() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setOrderNo("PO001")
                .setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED)
                .setSupplierId("S1");
        order.setId("1");
        doReturn(order).when(service).getById("1");
        doReturn(1).when(service).claimForSync(any(), any());
        doReturn(true).when(service).updateById(any());
        // claimAndSync 会为同步报文回填供应商信息，故必须注入供应商 Mapper
        WmsSupplier supplier = new WmsSupplier().setSupplierName("永鑫五金").setSupplierCode("SUP001");
        supplier.setId("S1");
        WmsSupplierMapper supplierMapper = mock(WmsSupplierMapper.class);
        when(supplierMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(supplier));
        service.setSupplierMapperForTest(supplierMapper);

        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(syncService.syncOrder(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(false, "ERP 未配置", 3));
        service.setSyncServiceForTest(syncService);

        WmsOrderSyncOutcomeVO outcome = service.dispatchOrder("1");

        verify(syncService, times(1)).syncOrder(any(), eq(true));
        assertEquals(false, outcome.getSynced());
        assertEquals("ERP 未配置", outcome.getSyncError());
        assertEquals(3, outcome.getRetryCount());

        // 审查发现：传给同步的快照必须带齐报文要素，
        // 否则一旦接入真实 ERP/MES，对方会收到空供应商编码/名称与空下达时间
        ArgumentCaptor<WmsOrder> snapshotCaptor = ArgumentCaptor.forClass(WmsOrder.class);
        verify(syncService).syncOrder(snapshotCaptor.capture(), eq(true));
        WmsOrder snapshot = snapshotCaptor.getValue();
        assertEquals("永鑫五金", snapshot.getSupplierName(), "同步报文需要供应商名称");
        assertEquals("SUP001", snapshot.getSupplierCode(), "同步报文需要供应商编码");
        assertNotNull(snapshot.getDispatchTime(), "同步报文需要下达时间（下达路径此前恒为 null）");
        // 不下断言 dispatchBy：单测无 Shiro 安全上下文，WmsUtils.currentUsername() 按设计返回 null，
        // 该字段的真实取值由端到端（已登录）覆盖，见验收报告 §2。
    }

    /** 只有「已审核」可下达。 */
    @Test
    void dispatchOrder_shouldRejectNonAudited() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setId("1");
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.dispatchOrder("1"));
        assertEquals("仅已审核状态的订单可下达", ex.getMessage());
    }

    /** 只有「已下达 且 同步失败/待同步」可重新同步。 */
    @Test
    void resyncOrder_shouldRejectNonFailedStates() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder()
                .setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED)
                .setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS);
        order.setId("1");
        doReturn(order).when(service).getById("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resyncOrder("1"));
        assertEquals("仅同步失败或待同步的订单可重新同步", ex.getMessage());
    }

    // ============================================================ 新增：必填与类型（BD002-T08/T09/T10）

    /** 订单号为空白必须拒绝——此前只测了重复，没测必填。 */
    @Test
    void addOrder_shouldRejectBlankOrderNo() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderNo("   ").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.addOrder(order));
        assertEquals("订单号不能为空", ex.getMessage());
    }

    /** 三种订单类型都必须可创建（公司订单与委外订单此前未单独覆盖）。 */
    @Test
    void addOrder_shouldAcceptAllAllowedTypes() {
        for (String type : new String[]{
                WmsConstants.ORDER_TYPE_COMPANY,
                WmsConstants.ORDER_TYPE_PURCHASE,
                WmsConstants.ORDER_TYPE_OUTSOURCE}) {
            WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
            doReturn(false).when(service).checkOrderNoExists(any(), any());
            doReturn(true).when(service).save(any());

            WmsOrder order = new WmsOrder().setOrderNo("PO001").setOrderType(type);
            service.addOrder(order);

            assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, order.getOrderStatus(),
                    "类型「" + type + "」应可创建且落到待审核");
            verify(service).save(order);
        }
    }

    /**
     * 新增必须清空 id 与审核/下达/同步的历史痕迹。
     *
     * <p>否则复用同一个实体对象（如前端提交、或从已下达订单复制）时会把旧痕迹带进新单。
     */
    @Test
    void addOrder_shouldClearAuditAndSyncTrace() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsOrder order = new WmsOrder().setOrderNo("PO001").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE)
                .setAuditBy("张三").setAuditRemark("旧意见").setAuditTime(new Date())
                .setDispatchBy("李四").setDispatchTime(new Date())
                .setLastSyncError("旧错误").setLastSyncTime(new Date())
                .setSyncStatus(WmsConstants.SYNC_STATUS_SUCCESS).setRetryCount(9);
        order.setId("客户端伪造ID");
        order.setDelFlag(1);

        service.addOrder(order);

        assertNull(order.getId(), "新增必须清空 id");
        assertEquals(0, order.getDelFlag());
        assertNull(order.getAuditBy());
        assertNull(order.getAuditTime());
        assertNull(order.getAuditRemark());
        assertNull(order.getDispatchBy());
        assertNull(order.getDispatchTime());
        assertNull(order.getLastSyncError());
        assertNull(order.getLastSyncTime());
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, order.getSyncStatus());
        assertEquals(0, order.getRetryCount());
    }

    // ============================================================ 编辑

    /** 待审核订单可编辑，且状态保持待审核。 */
    @Test
    void editOrder_pendingAuditShouldStayPending() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        existing.setId("1");
        doReturn(existing).when(service).getById("1");
        doReturn(false).when(service).checkOrderNoExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsOrder input = new WmsOrder().setOrderNo("PO001").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
        input.setId("1");
        service.editOrder(input);

        assertEquals(WmsConstants.ORDER_STATUS_PENDING_AUDIT, input.getOrderStatus());
        assertNull(input.getAuditRemark(), "编辑必须清空上一轮审核意见");
        verify(service).updateById(input);
    }

    /** 订单不存在必须明确报错，而不是静默更新 0 行。 */
    @Test
    void editOrder_shouldRejectMissingOrder() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(null).when(service).getById("404");

        WmsOrder input = new WmsOrder().setOrderNo("PO001").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
        input.setId("404");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input));
        assertEquals("未找到对应订单", ex.getMessage());
    }

    /** 编辑时订单号撞上别的订单必须拒绝。 */
    @Test
    void editOrder_shouldRejectDuplicatedOrderNo() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        existing.setId("1");
        doReturn(existing).when(service).getById("1");
        doReturn(true).when(service).checkOrderNoExists(any(), any());

        WmsOrder input = new WmsOrder().setOrderNo("PO002").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
        input.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input));
        assertEquals("订单号已存在，请重新输入", ex.getMessage());
        verify(service, never()).updateById(any());
    }

    /** 编辑时订单类型非法必须拒绝。 */
    @Test
    void editOrder_shouldRejectUnknownOrderType() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        existing.setId("1");
        doReturn(existing).when(service).getById("1");

        WmsOrder input = new WmsOrder().setOrderNo("PO001").setOrderType("不存在的类型");
        input.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input));
        assertEquals("订单类型不合法", ex.getMessage());
    }

    /** 编辑时订单号被清空必须拒绝。 */
    @Test
    void editOrder_shouldRejectBlankOrderNo() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder existing = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        existing.setId("1");
        doReturn(existing).when(service).getById("1");

        WmsOrder input = new WmsOrder().setOrderNo("  ").setOrderType(WmsConstants.ORDER_TYPE_PURCHASE);
        input.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editOrder(input));
        assertEquals("订单号不能为空", ex.getMessage());
    }

    // ============================================================ 审核参数校验

    /** 审核入参缺 id 必须拒绝。 */
    @Test
    void auditOrder_shouldRejectMissingId() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setPass(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto));
        assertEquals("订单ID不能为空", ex.getMessage());
    }

    /** 审核未选择结果必须拒绝。 */
    @Test
    void auditOrder_shouldRejectMissingPass() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto));
        assertEquals("请选择审核结果", ex.getMessage());
    }

    /** 订单不存在必须明确报错。 */
    @Test
    void auditOrder_shouldRejectMissingOrder() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(null).when(service).getById("404");
        WmsOrderAuditDTO dto = new WmsOrderAuditDTO();
        dto.setId("404");
        dto.setPass(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.auditOrder(dto));
        assertEquals("未找到对应订单", ex.getMessage());
    }

    // ============================================================ 删除

    /** 待审核订单可删除。 */
    @Test
    void removeOrder_shouldRemovePendingAudit() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder order = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        order.setId("1");
        doReturn(order).when(service).getById("1");
        doReturn(true).when(service).removeById("1");

        service.removeOrder("1");

        verify(service).removeById("1");
    }

    /** 订单不存在必须明确报错。 */
    @Test
    void removeOrder_shouldRejectMissingOrder() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(null).when(service).getById("404");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeOrder("404"));
        assertEquals("未找到对应订单", ex.getMessage());
    }

    /** 空/null 批量删除必须安全返回。 */
    @Test
    void removeBatch_shouldNoOpOnNullOrEmpty() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());

        service.removeBatch(null);
        service.removeBatch(Collections.emptyList());

        verify(service, never()).removeById(any());
    }

    /**
     * 批量删除逐个走状态守卫：遇到不可删的状态时**已删的部分不回滚**（当前实现无事务）。
     *
     * <p>这是验收报告 §7 记录的既知 Minor（「批量删除未按状态预过滤」）——本用例把它固定下来，
     * 使将来改为「先预过滤再删」时能被立刻发现。
     */
    @Test
    void removeBatch_shouldFailFastOnNonRemovableState() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        WmsOrder removable = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_PENDING_AUDIT);
        removable.setId("1");
        WmsOrder dispatched = new WmsOrder().setOrderStatus(WmsConstants.ORDER_STATUS_DISPATCHED);
        dispatched.setId("2");
        doReturn(removable).when(service).getById("1");
        doReturn(dispatched).when(service).getById("2");
        doReturn(true).when(service).removeById("1");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.removeBatch(Arrays.asList("1", "2")));

        assertEquals("仅待审核或已驳回状态的订单可删除", ex.getMessage());
        verify(service).removeById("1");
        verify(service, never()).removeById("2");
    }

    // ============================================================ 同步结果落库

    /** 同步成功落库：状态置成功、清空错误原因、刷新时间。 */
    @Test
    void persistSyncResult_successShouldClearError() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(true).when(service).updateById(any());

        service.persistSyncResult("1", new WmsOrderSyncAttempt(true, null, 0));

        ArgumentCaptor<WmsOrder> captor = ArgumentCaptor.forClass(WmsOrder.class);
        verify(service).updateById(captor.capture());
        WmsOrder saved = captor.getValue();
        assertEquals(WmsConstants.SYNC_STATUS_SUCCESS, saved.getSyncStatus());
        assertNull(saved.getLastSyncError(), "同步成功后必须清掉上一次的失败原因");
        assertEquals(0, saved.getRetryCount());
        assertNotNull(saved.getLastSyncTime());
    }

    /** 同步失败落库：状态置失败、写入原因与重试次数。 */
    @Test
    void persistSyncResult_failureShouldStoreError() {
        WmsOrderServiceImpl service = spy(new WmsOrderServiceImpl());
        doReturn(true).when(service).updateById(any());

        service.persistSyncResult("1", new WmsOrderSyncAttempt(false, "ERP 挂了", 3));

        ArgumentCaptor<WmsOrder> captor = ArgumentCaptor.forClass(WmsOrder.class);
        verify(service).updateById(captor.capture());
        WmsOrder saved = captor.getValue();
        assertEquals(WmsConstants.SYNC_STATUS_FAILED, saved.getSyncStatus());
        assertEquals("ERP 挂了", saved.getLastSyncError());
        assertEquals(3, saved.getRetryCount());
    }

    // ============================================================ 僵死阈值与 Mapper 委托

    /** 僵死阈值必须与常量口径一致（补偿任务复用同一入口，两处不得漂移）。 */
    @Test
    void syncStaleBefore_shouldSubtractConfiguredTimeout() {
        WmsOrderServiceImpl service = new WmsOrderServiceImpl();
        long before = System.currentTimeMillis();

        Date staleBefore = service.syncStaleBefore();

        long expected = before - WmsConstants.SYNC_RECLAIM_TIMEOUT_SECONDS * 1000L;
        assertTrue(Math.abs(staleBefore.getTime() - expected) < 5000,
                "僵死阈值应为 " + WmsConstants.SYNC_RECLAIM_TIMEOUT_SECONDS + " 秒前，实际差值 "
                        + (before - staleBefore.getTime()) + " ms");
    }

    /** 认领与候选扫描必须委托给 Mapper，服务层不自行拼 SQL。 */
    @Test
    void claimAndScan_shouldDelegateToMapper() {
        WmsOrderServiceImpl service = new WmsOrderServiceImpl();
        WmsOrderMapper mapper = mock(WmsOrderMapper.class);
        WmsTestSupport.inject(service, "baseMapper", mapper);
        Date staleBefore = new Date();
        when(mapper.claimForSync("1", staleBefore)).thenReturn(1);
        when(mapper.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH))
                .thenReturn(Collections.emptyList());

        assertEquals(1, service.claimForSync("1", staleBefore));
        assertTrue(service.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH).isEmpty());
        verify(mapper).claimForSync("1", staleBefore);
        verify(mapper).selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
    }
}
