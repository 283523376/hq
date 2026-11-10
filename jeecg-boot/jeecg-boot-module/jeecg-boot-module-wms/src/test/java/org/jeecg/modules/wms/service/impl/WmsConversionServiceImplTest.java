package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsConversionCreateDTO;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsConversionDetail;
import org.jeecg.modules.wms.entity.WmsConversionOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsConversionDetailMapper;
import org.jeecg.modules.wms.mapper.WmsConversionOrderMapper;
import org.jeecg.modules.wms.service.IWmsMaterialService;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 改制单（大改小，V2.4 spec §3.1）单测。
 *
 * <p>锁六件事：
 * <ol>
 *   <li>建单校验：生产订单必须已同步且已下达，投入/产出物料存在、产出物料=生产订单成品；</li>
 *   <li>建单显式置 DRAFT 并写投入/产出两条明细；</li>
 *   <li>完成只允许 DRAFT→DONE，且只生成一张生产入库单（重复完成/并发认领失败都不生成第二张）；</li>
 *   <li>完成传给生产入库的 DTO：物料=生产订单（=产出物料）、数量=产出数量、
 *       半成品 → 立库、备注=「改制单:单号」；</li>
 *   <li>取消只允许 DRAFT→CANCELLED，取消后完成被拒；</li>
 *   <li>状态机穷举：DRAFT/RUNNING/DONE/CANCELLED × complete/cancel 全路径。</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsConversionServiceImplTest {

    private static final String OPERATOR = "tester";
    private static final String CONVERSION_NO = "GZ2026110400001";
    private static final String ORDER_ID = "gz1";

    private WmsConversionOrderMapper orderMapper;
    private WmsConversionDetailMapper detailMapper;
    private IWmsWorkOrderService workOrderService;
    private IWmsMaterialService materialService;
    private IWmsProductionInboundService productionInboundService;

    private WmsConversionServiceImpl spy;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsConversionOrderMapper.class);
        detailMapper = mock(WmsConversionDetailMapper.class);
        workOrderService = mock(IWmsWorkOrderService.class);
        materialService = mock(IWmsMaterialService.class);
        productionInboundService = mock(IWmsProductionInboundService.class);

        WmsConversionServiceImpl service = new WmsConversionServiceImpl(detailMapper,
                workOrderService, materialService, productionInboundService);
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        spy = Mockito.spy(service);
        doReturn(CONVERSION_NO).when(spy).nextConversionNo();

        when(orderMapper.insert(any(WmsConversionOrder.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, WmsConversionOrder.class).setId(ORDER_ID);
            return 1;
        });
        when(detailMapper.insert(any(WmsConversionDetail.class))).thenReturn(1);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(materialService.checkCodeExists(anyString(), any())).thenReturn(true);
    }

    // ------------------------------------------------------------------ 建单

    @Test
    void createSetsDraftStatusAndWritesInOutDetails() {
        WmsConversionCreateDTO dto = dto();
        dto.setProductionOrderNo(" MO101 ");
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_RELEASED, "M-FG"));

        WmsConversionOrder order = spy.create(dto, OPERATOR);

        ArgumentCaptor<WmsConversionOrder> orderCaptor = ArgumentCaptor.forClass(WmsConversionOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        WmsConversionOrder saved = orderCaptor.getValue();
        // 状态显式置 DRAFT（不依赖 DDL 默认值）
        assertEquals(WmsConstants.CONVERSION_STATUS_DRAFT, saved.getStatus());
        assertEquals(CONVERSION_NO, saved.getConversionNo());
        assertEquals("MO101", saved.getProductionOrderNo());
        assertEquals(OPERATOR, saved.getOperator());
        assertEquals(ORDER_ID, order.getId());

        ArgumentCaptor<WmsConversionDetail> detailCaptor = ArgumentCaptor.forClass(WmsConversionDetail.class);
        verify(detailMapper, times(2)).insert(detailCaptor.capture());
        List<WmsConversionDetail> rows = detailCaptor.getAllValues();
        assertEquals(WmsConstants.CONVERSION_DIRECTION_IN, rows.get(0).getDirection());
        assertEquals(ORDER_ID, rows.get(0).getConversionId());
        assertEquals("M-RAW", rows.get(0).getMaterialCode());
        assertEquals("BIN1", rows.get(0).getBatchNo());
        assertEquals(0, new BigDecimal("50").compareTo(rows.get(0).getQty()));
        assertEquals(WmsConstants.CONVERSION_DIRECTION_OUT, rows.get(1).getDirection());
        assertEquals("M-FG", rows.get(1).getMaterialCode());
        assertEquals("BOUT1", rows.get(1).getBatchNo());
        assertEquals(0, new BigDecimal("20").compareTo(rows.get(1).getQty()));
    }

    @Test
    void createRejectsUnknownProductionOrder() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto(), OPERATOR));

        assertTrue(e.getMessage().contains("未同步"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
        verify(detailMapper, never()).insert(any(WmsConversionDetail.class));
    }

    @Test
    void createRejectsNotReleasedWorkOrder() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_PENDING, "M-FG"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto(), OPERATOR));

        assertTrue(e.getMessage().contains("未下达"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
    }

    @Test
    void createRejectsCancelledWorkOrder() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_CANCELLED, "M-FG"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto(), OPERATOR));

        assertTrue(e.getMessage().contains("已作废"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
    }

    @Test
    void createRejectsOutputMaterialMismatch() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_RELEASED, "M-OTHER"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto(), OPERATOR));

        assertTrue(e.getMessage().contains("一致"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
    }

    @Test
    void createRejectsNonPositiveOutputQty() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_RELEASED, "M-FG"));
        WmsConversionCreateDTO dto = dto();
        dto.setOutputQty(BigDecimal.ZERO);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto, OPERATOR));

        assertTrue(e.getMessage().contains("产出数量"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
    }

    @Test
    void createRejectsUnknownOutputMaterial() {
        when(workOrderService.getByWorkOrderNo("MO101")).thenReturn(
                workOrder(WmsConstants.WORK_ORDER_STATUS_RELEASED, "M-FG"));
        when(materialService.checkCodeExists("M-FG", null)).thenReturn(false);

        RuntimeException e = assertThrows(RuntimeException.class, () -> spy.create(dto(), OPERATOR));

        assertTrue(e.getMessage().contains("产出物料不存在"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsConversionOrder.class));
    }

    // ------------------------------------------------------------------ 完成

    @Test
    void completeSubmitsProductionInboundWithConversionTrace() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DRAFT));

        spy.complete(ORDER_ID, OPERATOR);

        // 条件认领（status='DRAFT'）被调用一次
        verify(orderMapper).update(any(), any());
        ArgumentCaptor<WmsProductionSubmitDTO> captor = ArgumentCaptor.forClass(WmsProductionSubmitDTO.class);
        verify(productionInboundService).submit(captor.capture());
        WmsProductionSubmitDTO dto = captor.getValue();
        assertEquals("MO101", dto.getWorkOrderNo());
        assertEquals(0, new BigDecimal("20").compareTo(dto.getGoodQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(dto.getBadQty()));
        // 物料=产出物料：由生产订单（=产出物料）带出，这里只锁批次与溯源字段
        assertEquals("BOUT1", dto.getBatchNo());
        assertEquals(WmsConstants.PRODUCT_TYPE_SEMI, dto.getProductType());
        assertEquals(CONVERSION_NO, dto.getCarrierCode());
        assertEquals("改制单:" + CONVERSION_NO, dto.getRemark());
        // 技术债 §5-23：结构化关联必须带上，反查不能再依赖 remark 字符串
        assertEquals(ORDER_ID, dto.getConversionOrderId());
        assertEquals("M-RAW", dto.getSourceMaterialCode());
    }

    @Test
    void repeatedCompleteDoesNotGenerateSecondInbound() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DONE));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.complete(ORDER_ID, OPERATOR));

        assertEquals("改制单已完成", e.getMessage());
        verify(orderMapper, never()).update(any(), any());
        verify(productionInboundService, never()).submit(any());
    }

    @Test
    void lostClaimDoesNotGenerateSecondInbound() {
        // 单据行仍读出 DRAFT，但条件认领 0 行：并发下别人刚完成
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DRAFT));
        when(orderMapper.update(any(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.complete(ORDER_ID, OPERATOR));

        assertEquals("改制单已完成", e.getMessage());
        verify(productionInboundService, never()).submit(any());
    }

    @Test
    void completeRejectsMissingOrder() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.complete(ORDER_ID, OPERATOR));

        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
        verify(productionInboundService, never()).submit(any());
    }

    // ------------------------------------------------------------------ 取消与状态机

    @Test
    void cancelMovesDraftToCancelledWithoutInbound() {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DRAFT));

        spy.cancel(ORDER_ID, OPERATOR);

        verify(orderMapper).update(any(), any());
        verify(productionInboundService, never()).submit(any());
    }

    @Test
    void cancelRejectsOnDoneOrRunning() {
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DONE))
                .thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_RUNNING));

        RuntimeException done = assertThrows(RuntimeException.class,
                () -> spy.cancel(ORDER_ID, OPERATOR));
        assertTrue(done.getMessage().contains("不允许取消"), done.getMessage());
        RuntimeException running = assertThrows(RuntimeException.class,
                () -> spy.cancel(ORDER_ID, OPERATOR));
        assertTrue(running.getMessage().contains("不允许取消"), running.getMessage());
        verify(productionInboundService, never()).submit(any());
    }

    @Test
    void completeAfterCancelIsRejected() {
        // 第一次读到 DRAFT（取消成功），第二次读到 CANCELLED（完成必须被拒）
        when(orderMapper.selectById(ORDER_ID))
                .thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_DRAFT))
                .thenReturn(orderWithStatus(WmsConstants.CONVERSION_STATUS_CANCELLED));

        spy.cancel(ORDER_ID, OPERATOR);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> spy.complete(ORDER_ID, OPERATOR));

        assertTrue(e.getMessage().contains("不允许完成"), e.getMessage());
        assertTrue(e.getMessage().contains(WmsConstants.CONVERSION_STATUS_CANCELLED), e.getMessage());
        verify(productionInboundService, never()).submit(any());
    }

    @Test
    void transitionCoversAllStatesAndActions() {
        assertEquals(WmsConstants.CONVERSION_STATUS_DONE,
                WmsConversionServiceImpl.transition(WmsConversionServiceImpl.ACTION_COMPLETE,
                        WmsConstants.CONVERSION_STATUS_DRAFT));
        assertEquals(WmsConstants.CONVERSION_STATUS_CANCELLED,
                WmsConversionServiceImpl.transition(WmsConversionServiceImpl.ACTION_CANCEL,
                        WmsConstants.CONVERSION_STATUS_DRAFT));

        RuntimeException done = assertThrows(RuntimeException.class, () -> WmsConversionServiceImpl
                .transition(WmsConversionServiceImpl.ACTION_COMPLETE, WmsConstants.CONVERSION_STATUS_DONE));
        assertEquals("改制单已完成", done.getMessage());

        for (String state : new String[] {
                WmsConstants.CONVERSION_STATUS_CANCELLED, WmsConstants.CONVERSION_STATUS_RUNNING}) {
            RuntimeException complete = assertThrows(RuntimeException.class,
                    () -> WmsConversionServiceImpl.transition(WmsConversionServiceImpl.ACTION_COMPLETE, state));
            assertTrue(complete.getMessage().contains("不允许完成"), complete.getMessage());
            RuntimeException cancel = assertThrows(RuntimeException.class,
                    () -> WmsConversionServiceImpl.transition(WmsConversionServiceImpl.ACTION_CANCEL, state));
            assertTrue(cancel.getMessage().contains("不允许取消"), cancel.getMessage());
        }
    }

    // ------------------------------------------------------------------ 辅助

    private WmsConversionCreateDTO dto() {
        WmsConversionCreateDTO dto = new WmsConversionCreateDTO();
        dto.setProductionOrderNo("MO101");
        dto.setInputMaterialCode("M-RAW");
        dto.setInputBatchNo("BIN1");
        dto.setInputQty(new BigDecimal("50"));
        dto.setOutputMaterialCode("M-FG");
        dto.setOutputBatchNo("BOUT1");
        dto.setOutputQty(new BigDecimal("20"));
        return dto;
    }

    private WmsWorkOrder workOrder(String status, String materialCode) {
        WmsWorkOrder order = new WmsWorkOrder()
                .setWorkOrderNo("MO101")
                .setMaterialCode(materialCode)
                .setPlanQty(new BigDecimal("100"))
                .setWorkOrderStatus(status);
        order.setId("wo1");
        return order;
    }

    private WmsConversionOrder orderWithStatus(String status) {
        WmsConversionOrder order = new WmsConversionOrder()
                .setConversionNo(CONVERSION_NO)
                .setProductionOrderNo("MO101")
                .setInputMaterialCode("M-RAW")
                .setInputQty(new BigDecimal("50"))
                .setOutputMaterialCode("M-FG")
                .setOutputBatchNo("BOUT1")
                .setOutputQty(new BigDecimal("20"))
                .setStatus(status);
        order.setId(ORDER_ID);
        return order;
    }
}
