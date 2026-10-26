package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点建单——账面快照、明细粒度、库位冻结（spec §5.3）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeCreateTest {

    private WmsStocktakeOrderMapper orderMapper;
    private WmsStocktakeDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsLocationMapper locationMapper;
    private WmsStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        service = new WmsStocktakeServiceImpl(orderMapper, detailMapper, inventoryMapper,
                locationMapper, new WmsStocktakeStatusResolver());
        when(orderMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void shouldSnapshotBookQtyAndFreezeLocations() {
        when(inventoryMapper.selectList(any())).thenReturn(List.of(
                stock("loc-1", "M1", "B1", null, "10"),
                stock("loc-1", "M2", "B2", "C1", "5")));
        when(locationMapper.selectById("loc-1")).thenReturn(
                new WmsLocation().setLocationCode("A-01-01"));

        WmsStocktakeOrder order = service.create(dto("loc-1"), "alice");

        assertEquals(WmsConstants.STOCKTAKE_STATUS_DRAFT, order.getStatus());
        assertEquals(0, order.getBookTotal().compareTo(new BigDecimal("15")));
        assertTrue(order.getStocktakeNo().startsWith("PD"), order.getStocktakeNo());

        ArgumentCaptor<WmsStocktakeDetail> detailCaptor = ArgumentCaptor.forClass(WmsStocktakeDetail.class);
        verify(detailMapper, org.mockito.Mockito.times(2)).insert(detailCaptor.capture());
        assertEquals(0, detailCaptor.getAllValues().get(0).getBookQty().compareTo(new BigDecimal("10")));
        assertEquals("C1", detailCaptor.getAllValues().get(1).getContainerCode());

        ArgumentCaptor<WmsLocation> locCaptor = ArgumentCaptor.forClass(WmsLocation.class);
        verify(locationMapper, atLeastOnce()).updateById(locCaptor.capture());
        assertTrue(locCaptor.getAllValues().stream()
                .anyMatch(l -> WmsConstants.STOCKTAKE_FROZEN.equals(l.getStocktakeFrozen())));
    }

    @Test
    void shouldRejectEmptyScope() {
        WmsStocktakeCreateDTO dto = new WmsStocktakeCreateDTO();
        assertThrows(RuntimeException.class, () -> service.create(dto, "alice"));
    }

    @Test
    void startShouldMoveDraftToCounting() {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-1");
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_DRAFT);
        when(orderMapper.selectById("st-1")).thenReturn(order);

        service.start("st-1");

        assertEquals(WmsConstants.STOCKTAKE_STATUS_COUNTING, order.getStatus());
        verify(orderMapper).updateById(order);
    }

    @Test
    void startShouldRejectArchivedOrder() {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-2");
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_ARCHIVED);
        when(orderMapper.selectById("st-2")).thenReturn(order);

        assertThrows(RuntimeException.class, () -> service.start("st-2"));
    }

    private static WmsInventory stock(String locId, String matId, String batch, String container, String qty) {
        WmsInventory inventory = new WmsInventory();
        inventory.setLocationId(locId);
        inventory.setMaterialId(matId);
        inventory.setBatchNo(batch);
        inventory.setContainerCode(container);
        inventory.setQuantity(new BigDecimal(qty));
        return inventory;
    }

    private static WmsStocktakeCreateDTO dto(String... locationIds) {
        WmsStocktakeCreateDTO dto = new WmsStocktakeCreateDTO();
        dto.setStocktakeType(WmsConstants.STOCKTAKE_TYPE_PERIODIC);
        dto.setLocationIds(List.of(locationIds));
        return dto;
    }
}
