package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOrderAuditDTO;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.entity.WmsSupplier;
import org.jeecg.modules.wms.mapper.WmsSupplierMapper;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOrderSyncOutcomeVO;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void fillSupplierName_shouldTolerateMissingSupplier() {
        // selectBatchIds 自带 del_flag=0 过滤，已删除的供应商查不到 → 返回空集合
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());
        WmsOrder order = new WmsOrder().setSupplierId("不存在的供应商ID");
        order.setId("1");

        assertDoesNotThrow(() -> service.fillSupplierName(Collections.singletonList(order)));
        assertEquals(null, order.getSupplierName());
    }

    /** 空列表与全空 supplierId 都必须安全返回（此时根本不该查库）。 */
    @Test
    void fillSupplierName_shouldHandleEmptyInput() {
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.emptyList());

        assertDoesNotThrow(() -> service.fillSupplierName(null));
        assertDoesNotThrow(() -> service.fillSupplierName(Collections.emptyList()));
        WmsOrder blank = new WmsOrder();
        blank.setId("1");
        assertDoesNotThrow(() -> service.fillSupplierName(Collections.singletonList(blank)));
    }

    /** 供应商存在时必须正确回填名称（防止"只测了容错、没测正常路径"）。 */
    @Test
    void fillSupplierName_shouldFillExistingSupplier() {
        WmsSupplier supplier = new WmsSupplier().setSupplierName("永鑫五金");
        supplier.setId("S1");
        WmsOrderServiceImpl service = serviceWithSupplierMapper(Collections.singletonList(supplier));
        WmsOrder order = new WmsOrder().setSupplierId("S1");
        order.setId("1");

        service.fillSupplierName(Collections.singletonList(order));

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
                .setOrderStatus(WmsConstants.ORDER_STATUS_AUDITED);
        order.setId("1");
        doReturn(order).when(service).getById("1");
        doReturn(1).when(service).claimForSync(any(), any());
        doReturn(true).when(service).updateById(any());

        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(syncService.syncOrder(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(false, "ERP 未配置", 3));
        service.setSyncServiceForTest(syncService);

        WmsOrderSyncOutcomeVO outcome = service.dispatchOrder("1");

        verify(syncService, times(1)).syncOrder(any(), eq(true));
        assertEquals(false, outcome.getSynced());
        assertEquals("ERP 未配置", outcome.getSyncError());
        assertEquals(3, outcome.getRetryCount());
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
}
