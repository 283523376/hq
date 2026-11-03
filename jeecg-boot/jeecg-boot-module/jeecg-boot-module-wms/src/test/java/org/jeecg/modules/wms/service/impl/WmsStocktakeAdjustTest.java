package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInventory;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点库存调整——按实盘覆盖库存，绝不产生出入库单据（spec §5.3 / D10）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeAdjustTest {

    private WmsStocktakeOrderMapper orderMapper;
    private WmsStocktakeDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        service = new WmsStocktakeServiceImpl(orderMapper, detailMapper, inventoryMapper,
                mock(WmsLocationMapper.class), new WmsStocktakeStatusResolver());
    }

    private void stubOrder(String status) {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-1");
        order.setStatus(status);
        when(orderMapper.selectById("st-1")).thenReturn(order);
    }

    private WmsStocktakeDetail detail(String book, String actual) {
        WmsStocktakeDetail detail = new WmsStocktakeDetail();
        detail.setId("d1");
        detail.setStocktakeId("st-1");
        detail.setLocationId("loc-1");
        detail.setMaterialId("m1");
        detail.setBatchNo("B1");
        detail.setBookQty(new BigDecimal(book));
        detail.setActualQty(actual == null ? null : new BigDecimal(actual));
        return detail;
    }

    @Test
    void adjustShouldOverwriteInventoryWithActualQty() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("10", "7")));
        WmsInventory existing = new WmsInventory();
        existing.setId("inv-1");
        when(inventoryMapper.selectOne(any())).thenReturn(existing);

        service.applyAdjustment("st-1", "alice");

        // 2026-11-03 SB-001 前置修复：覆盖改走 touchQuantity（显式刷 update_time/update_by），
        // 不再走通用 updateById（无自动填充，审计列不变，对账会漏掉盘点调整）
        verify(inventoryMapper).touchQuantity("inv-1", new BigDecimal("7"), "alice");
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));

        WmsStocktakeOrder order = orderAfterAdjust();
        assertEquals(WmsConstants.STOCKTAKE_STATUS_ADJUSTED, order.getStatus());
    }

    @Test
    void adjustZeroActualShouldDeleteInventoryRow() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("10", "0")));
        WmsInventory existing = new WmsInventory();
        existing.setId("inv-1");
        when(inventoryMapper.selectOne(any())).thenReturn(existing);

        service.applyAdjustment("st-1", "alice");

        verify(inventoryMapper).deleteById("inv-1");
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
    }

    @Test
    void adjustUnrecordedRowShouldBeSkipped() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("10", null)));

        service.applyAdjustment("st-1", "alice");

        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
        verify(inventoryMapper, never()).deleteById(any(String.class));
        verify(inventoryMapper, never()).insert(any(WmsInventory.class));
    }

    @Test
    void adjustRequiresDiffConfirmedState() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_COUNTING);
        assertThrows(RuntimeException.class, () -> service.applyAdjustment("st-1", "alice"));
    }

    @Test
    void adjustShouldInsertInventoryWhenRowMissing() {
        stubOrder(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("0", "12")));
        // selectOne 未打桩 → 返回 null：库存行不存在，盘盈走插入

        service.applyAdjustment("st-1", "alice");

        // spec D10：盘点调整只改库存，绝不建真单据（会触发设备下发与错误回写）
        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getQuantity().compareTo(new BigDecimal("12")));
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
    }

    private WmsStocktakeOrder orderAfterAdjust() {
        ArgumentCaptor<WmsStocktakeOrder> captor = ArgumentCaptor.forClass(WmsStocktakeOrder.class);
        verify(orderMapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        return captor.getValue();
    }
}
