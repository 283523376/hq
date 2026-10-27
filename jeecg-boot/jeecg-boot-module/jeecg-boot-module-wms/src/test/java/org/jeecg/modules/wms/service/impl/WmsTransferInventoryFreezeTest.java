package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferTask;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsTransferTaskMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 移库上架目标库位被盘点冻结时必须拦下（spec §5.3 三条写入路径之二）
 * @Author: 永鑫WMS
 * @Date 2026-10-26
 */
class WmsTransferInventoryFreezeTest {

    private WmsLocationMapper locationMapper;
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;
    private WmsTransferInventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        locationMapper = mock(WmsLocationMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        service = new WmsTransferInventoryServiceImpl(
                inventoryMapper, locationMapper,
                mock(WmsWarehouseMapper.class), mock(WmsTransferTaskMapper.class));
    }

    @Test
    void addToFrozenLocationIsRejected() {
        WmsLocation frozen = new WmsLocation();
        frozen.setId("loc-t1");
        frozen.setLocationCode("TC-01");
        frozen.setStocktakeFrozen(WmsConstants.STOCKTAKE_FROZEN);
        when(locationMapper.selectById("loc-t1")).thenReturn(frozen);

        WmsTransferTask task = new WmsTransferTask();
        task.setAllocatedQty(new BigDecimal("3"));
        WmsTransferDetail detail = new WmsTransferDetail();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.addToLocation(task, detail, "loc-t1", "TC-01"));

        assertTrue(e.getMessage().contains("盘点冻结"), e.getMessage());
        assertTrue(e.getMessage().contains("TC-01"), e.getMessage());
    }

    @Test
    void allocateFromFrozenSourceIsRejected() {
        WmsLocation frozen = new WmsLocation();
        frozen.setId("loc-s1");
        frozen.setLocationCode("TS-01");
        frozen.setStocktakeFrozen(WmsConstants.STOCKTAKE_FROZEN);
        when(locationMapper.selectById("loc-s1")).thenReturn(frozen);

        org.jeecg.modules.wms.vo.WmsInventoryAvailableVO row = new org.jeecg.modules.wms.vo.WmsInventoryAvailableVO();
        row.setId("inv-9");
        row.setMaterialId("m1");
        row.setLocationId("loc-s1");
        row.setLocationCode("TS-01");
        row.setQuantity(new BigDecimal("10"));
        row.setLockedQty(BigDecimal.ZERO);
        org.jeecg.modules.wms.entity.WmsTransferOrder order = new org.jeecg.modules.wms.entity.WmsTransferOrder();
        order.setId("tr-1");
        order.setOutWarehouseId("w1");
        org.jeecg.modules.wms.entity.WmsTransferDetail detail = new org.jeecg.modules.wms.entity.WmsTransferDetail();
        detail.setId("d1");
        detail.setMaterialId("m1");
        detail.setTransferQty(new BigDecimal("3"));

        when(inventoryMapper.selectAvailableByWarehouse(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(row));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.allocate(order, detail));

        assertTrue(e.getMessage().contains("盘点冻结"), e.getMessage());
    }
}
