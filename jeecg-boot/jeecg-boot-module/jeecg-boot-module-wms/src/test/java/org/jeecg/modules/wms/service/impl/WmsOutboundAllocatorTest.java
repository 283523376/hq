package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库 FIFO 分配：跨行拆分、库存不足、并发锁失败、容器过滤、库位占用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundAllocatorTest {

    private WmsInventoryMapper inventoryMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsLocationMapper locationMapper;
    private WmsOutboundAllocatorImpl allocator;

    @BeforeEach
    void setUp() {
        inventoryMapper = mock(WmsInventoryMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        allocator = new WmsOutboundAllocatorImpl(inventoryMapper, allocationMapper, detailMapper, locationMapper);
        when(allocationMapper.insert(any(WmsOutboundAllocation.class))).thenReturn(1);
        when(detailMapper.updateById(any(WmsOutboundDetail.class))).thenReturn(1);
        when(locationMapper.update(any(), any())).thenReturn(1);
    }

    private WmsOutboundOrder order(String type) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("LL1").setOutboundType(type)
                .setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE);
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail(String required) {
        WmsOutboundDetail detail = new WmsOutboundDetail().setOutboundId("o1").setLineNo(1)
                .setMaterialId("m1").setMaterialCode("PBC001")
                .setRequiredQty(new BigDecimal(required))
                .setAllocatedQty(BigDecimal.ZERO);
        detail.setId("d1");
        return detail;
    }

    private WmsInventoryAvailableVO row(String id, String qty, String locked, String container) {
        WmsInventoryAvailableVO vo = new WmsInventoryAvailableVO();
        vo.setId(id);
        vo.setMaterialId("m1");
        vo.setLocationId("loc-" + id);
        vo.setLocationCode("L-" + id);
        vo.setContainerCode(container);
        vo.setBatchNo("B1");
        vo.setQuantity(new BigDecimal(qty));
        vo.setLockedQty(new BigDecimal(locked));
        return vo;
    }

    @Test
    void allocate_splitsAcrossFifoRows() {
        when(inventoryMapper.selectAvailable(eq("m1"), isNull(), eq(WmsConstants.WAREHOUSE_TYPE_STEREO),
                eq(false), anyInt())).thenReturn(List.of(row("A", "10", "0", ""), row("B", "20", "0", "")));
        when(inventoryMapper.lockInventory(eq("A"), any())).thenReturn(1);
        when(inventoryMapper.lockInventory(eq("B"), any())).thenReturn(1);

        allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_PICK), List.of(detail("15")));

        verify(inventoryMapper).lockInventory("A", new BigDecimal("10"));
        verify(inventoryMapper).lockInventory("B", new BigDecimal("5"));
        ArgumentCaptor<WmsOutboundAllocation> captor = ArgumentCaptor.forClass(WmsOutboundAllocation.class);
        verify(allocationMapper, times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().get(0).getTaskNo().startsWith(WmsConstants.TASK_NO_PREFIX_WCS));
        assertEquals(0, captor.getAllValues().get(0).getAllocatedQty().compareTo(new BigDecimal("10")));
        assertEquals(0, captor.getAllValues().get(1).getAllocatedQty().compareTo(new BigDecimal("5")));
        assertEquals(WmsConstants.REVIEW_STATUS_PENDING, captor.getAllValues().get(0).getReviewStatus());
        verify(locationMapper, times(2)).update(any(), any());
    }

    @Test
    void allocate_insufficient_throwsAndStops() {
        when(inventoryMapper.selectAvailable(anyString(), isNull(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(List.of(row("A", "5", "0", "")));
        when(inventoryMapper.lockInventory(eq("A"), any())).thenReturn(1);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_PICK), List.of(detail("10"))));

        assertTrue(e.getMessage().contains("库存不足"));
        assertTrue(e.getMessage().contains("PBC001"));
    }

    @Test
    void allocate_concurrentLockZeroRows_throws() {
        when(inventoryMapper.selectAvailable(anyString(), isNull(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(List.of(row("A", "10", "0", "")));
        when(inventoryMapper.lockInventory(eq("A"), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_PICK), List.of(detail("10"))));

        assertTrue(e.getMessage().contains("占用"));
    }

    @Test
    void allocate_sales_requiresContainer() {
        when(inventoryMapper.selectAvailable(eq("m1"), isNull(), eq(WmsConstants.WAREHOUSE_TYPE_AGILE),
                eq(true), anyInt())).thenReturn(List.of(row("A", "10", "0", "CT1")));
        when(inventoryMapper.lockInventory(eq("A"), any())).thenReturn(1);

        allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_SALES), List.of(detail("3")));

        verify(inventoryMapper).selectAvailable("m1", null, WmsConstants.WAREHOUSE_TYPE_AGILE, true, 1000);
        ArgumentCaptor<WmsOutboundAllocation> captor = ArgumentCaptor.forClass(WmsOutboundAllocation.class);
        verify(allocationMapper).insert(captor.capture());
        assertEquals("CT1", captor.getValue().getContainerCode());
        assertTrue(captor.getValue().getTaskNo().startsWith(WmsConstants.TASK_NO_PREFIX_RMS));
    }

    @Test
    void allocate_zeroAvailableRows_throwsStockShortage() {
        when(inventoryMapper.selectAvailable(anyString(), isNull(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(List.of());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_PICK), List.of(detail("1"))));

        assertTrue(e.getMessage().contains("库存不足"));
    }

    @Test
    void allocate_frozenLocation_isRejected() {
        WmsLocation frozen = new WmsLocation();
        frozen.setId("loc-1");
        frozen.setLocationCode("L-1");
        frozen.setStocktakeFrozen(WmsConstants.STOCKTAKE_FROZEN);
        when(locationMapper.selectById("loc-1")).thenReturn(frozen);
        when(inventoryMapper.selectAvailable(any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(row("1", "10", "0", null)));

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> allocator.allocate(order(WmsConstants.OUTBOUND_TYPE_SALES), List.of(detail("5"))));

        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("盘点冻结"), e.getMessage());
        org.mockito.Mockito.verify(inventoryMapper, org.mockito.Mockito.never())
                .lockInventory(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(BigDecimal.class));
    }
}
