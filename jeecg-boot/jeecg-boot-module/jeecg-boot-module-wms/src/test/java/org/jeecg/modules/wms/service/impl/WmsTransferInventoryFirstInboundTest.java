package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferTask;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsTransferTaskMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 移库不是新入库：目标库存行必须继承源行的批次首次入库时间，
 * 否则超期预警的货龄会被重置（spec 2026-10-28 D3）。
 */
class WmsTransferInventoryFirstInboundTest {

    private WmsInventoryMapper inventoryMapper;
    private WmsLocationMapper locationMapper;
    private WmsTransferInventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        inventoryMapper = mock(WmsInventoryMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        service = new WmsTransferInventoryServiceImpl(inventoryMapper, locationMapper,
                mock(WmsWarehouseMapper.class), mock(WmsTransferTaskMapper.class));
    }

    @Test
    void targetRowInheritsSourceFirstInboundTime() {
        Date firstInbound = new Date(1_700_000_000_000L);
        WmsInventory source = new WmsInventory();
        source.setId("inv1");
        source.setFirstInboundTime(firstInbound);

        WmsTransferTask task = new WmsTransferTask();
        task.setInventoryId("inv1");
        task.setAllocatedQty(new BigDecimal("5"));

        WmsTransferDetail detail = new WmsTransferDetail();
        detail.setMaterialId("m1");
        detail.setBatchNo("B1");
        detail.setContainerCode("C1");

        when(locationMapper.selectById("loc2")).thenReturn(null);
        when(inventoryMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectById("inv1")).thenReturn(source);

        service.addToLocation(task, detail, "loc2", "L-02");

        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(captor.capture());
        WmsInventory created = captor.getValue();
        assertEquals(firstInbound, created.getFirstInboundTime());
        assertEquals("L-02", detail.getInLocationCode());
        assertEquals("loc2", detail.getInLocationId());
    }

    @Test
    void missingSourceLeavesFirstInboundTimeNullForUpsertDefault() {
        WmsTransferTask task = new WmsTransferTask();
        task.setInventoryId("gone");
        task.setAllocatedQty(BigDecimal.ONE);

        WmsTransferDetail detail = new WmsTransferDetail();
        detail.setMaterialId("m1");

        when(locationMapper.selectById("loc2")).thenReturn(null);
        when(inventoryMapper.selectList(any())).thenReturn(List.of());
        when(inventoryMapper.selectById("gone")).thenReturn(null);

        service.addToLocation(task, detail, "loc2", "L-02");

        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(captor.capture());
        // null 交给 upsert 的 COALESCE(#{firstInboundTime}, NOW()) 兜底
        assertEquals(null, captor.getValue().getFirstInboundTime());
    }
}
