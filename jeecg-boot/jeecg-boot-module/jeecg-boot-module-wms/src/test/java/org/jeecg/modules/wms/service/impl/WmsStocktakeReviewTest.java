package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 差异确认与复核闸门——阈值决定去向、复核人≠发起人、simulate 开关（spec D14）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeReviewTest {

    private WmsStocktakeOrderMapper orderMapper;
    private WmsStocktakeDetailMapper detailMapper;
    private WmsStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        service = new WmsStocktakeServiceImpl(orderMapper, detailMapper,
                mock(WmsInventoryMapper.class), mock(WmsLocationMapper.class),
                new WmsStocktakeStatusResolver());
    }

    private void stubOrder(String status, String initiator) {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-1");
        order.setStatus(status);
        order.setInitiator(initiator);
        order.setDiffReviewThreshold(new BigDecimal("5"));
        order.setBookTotal(new BigDecimal("100"));
        order.setGainTotal(new BigDecimal("2"));
        order.setLossTotal(new BigDecimal("3"));
        when(orderMapper.selectById("st-1")).thenReturn(order);
        when(detailMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void withinThresholdConfirmGoesToDiffConfirmed() {
        // 5% 差异，阈值 5 → (2+3)/100 = 5% >= 5% 其实超阈……改用盈 1 亏 0 走阈值内
        stubOrder(WmsConstants.STOCKTAKE_STATUS_COUNTING, "alice");
        WmsStocktakeOrder order = orderMapper.selectById("st-1");
        order.setGainTotal(new BigDecimal("1"));
        order.setLossTotal(BigDecimal.ZERO);

        service.confirmDiff("st-1", "alice");

        assertEquals(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, order.getStatus());
    }

    @Test
    void overThresholdConfirmGoesToReviewing() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_COUNTING, "alice");

        service.confirmDiff("st-1", "alice");

        assertEquals(WmsConstants.STOCKTAKE_STATUS_REVIEWING, orderMapper.selectById("st-1").getStatus());
    }

    @Test
    void reviewByInitiatorIsRejected() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_REVIEWING, "alice");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.review("st-1", "alice"));

        assertTrue(e.getMessage().contains("复核人"), e.getMessage());
    }

    @Test
    void reviewByOtherPersonPassesBackToDiffConfirmed() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_REVIEWING, "alice");

        service.review("st-1", "bob");

        ArgumentCaptor<WmsStocktakeOrder> captor = ArgumentCaptor.forClass(WmsStocktakeOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, captor.getValue().getStatus());
        assertEquals("bob", captor.getValue().getReviewer());
    }

    @Test
    void reviewRequiresReviewingState() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, "alice");

        assertThrows(RuntimeException.class, () -> service.review("st-1", "bob"));
    }

    @Test
    void archiveOnlyFromAdjusted() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_COUNTING, "alice");
        assertThrows(RuntimeException.class, () -> service.archive("st-1"));

        stubOrder(WmsConstants.STOCKTAKE_STATUS_ADJUSTED, "alice");
        service.archive("st-1");
        ArgumentCaptor<WmsStocktakeOrder> captor = ArgumentCaptor.forClass(WmsStocktakeOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(WmsConstants.STOCKTAKE_STATUS_ARCHIVED, captor.getValue().getStatus());
    }

    private void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
