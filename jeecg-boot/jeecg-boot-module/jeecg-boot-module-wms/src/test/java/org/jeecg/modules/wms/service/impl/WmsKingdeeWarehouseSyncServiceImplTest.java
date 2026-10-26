package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 仓库 ERP 同步单测——白名单空转、组织过滤、命中只改名不改码、未命中新建普通仓、
 *               本地档案初始化幂等（spec 2026-10-26 §5.1）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeeWarehouseSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private IWmsWarehouseService warehouseService;
    private WmsWarehouseMapper warehouseMapper;
    private IWmsSyncLogService syncLogService;
    private WmsKingdeeWarehouseSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        warehouseService = mock(IWmsWarehouseService.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsKingdeeWarehouseSyncServiceImpl(
                formClient, properties, warehouseService, warehouseMapper, syncLogService);
    }

    @Test
    void shouldSkipWithoutQueryWhenWhitelistBlank() {
        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(0, vo.getFetched());
        assertTrue(vo.getMessage().contains("白名单"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldFilterByOrgAndWhitelist() {
        properties.getMasterData().setOrgId("101");
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", Collections.emptyList(), null, null));

        service.syncStock();

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("BD_STOCK"), anyString(), filterCaptor.capture(), anyInt());
        String filter = filterCaptor.getValue();
        assertTrue(filter.contains("FUseOrgId.FNumber='101'"), filter);
        assertTrue(filter.contains("FNumber in"), filter);
        assertTrue(filter.contains("'X.2.4'"), filter);
    }

    @Test
    void shouldRenameMappedWarehouseAndKeepCode() {
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("02.001")));
        properties.getMasterData().getStockCodeMap().put("02.001", "MJ");

        WmsWarehouse existing = new WmsWarehouse();
        existing.setId("wh-mj");
        existing.setWarehouseCode("MJ");
        existing.setWarehouseName("成品敏捷仓");
        when(warehouseMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("02.001", "产成品仓")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(1, vo.getRenamed());
        assertEquals(0, vo.getCreated());
        ArgumentCaptor<WmsWarehouse> captor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(warehouseService).editWarehouse(captor.capture());
        WmsWarehouse updated = captor.getValue();
        assertEquals("MJ", updated.getWarehouseCode());
        assertEquals("产成品仓", updated.getWarehouseName());
        assertEquals("02.001", updated.getErpStockCode());
        verify(warehouseService, never()).addWarehouse(any());
    }

    @Test
    void shouldCreateUnmappedWarehouseAsNormal() {
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("X.2.4", "涂层产成品仓")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(1, vo.getCreated());
        assertEquals(0, vo.getRenamed());
        ArgumentCaptor<WmsWarehouse> captor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(warehouseService).addWarehouse(captor.capture());
        WmsWarehouse created = captor.getValue();
        assertEquals("X.2.4", created.getWarehouseCode());
        assertEquals("涂层产成品仓", created.getWarehouseName());
        assertEquals(WmsConstants.WAREHOUSE_TYPE_NORMAL, created.getWarehouseType());
        assertEquals("X.2.4", created.getErpStockCode());
    }

    @Test
    void shouldInitializeLocalArchivesIdempotently() {
        WmsWarehouse lb = warehouse("wh-lb", "LB", "半成品立库");
        WmsWarehouse mj = warehouse("wh-mj", "MJ", "成品敏捷仓");
        when(warehouseMapper.selectOne(any())).thenReturn(lb, mj, null, null);

        service.initLocalArchives();

        ArgumentCaptor<WmsWarehouse> editCaptor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(warehouseService, times(2)).editWarehouse(editCaptor.capture());
        List<WmsWarehouse> edited = editCaptor.getAllValues();
        assertEquals("半成品仓", edited.get(0).getWarehouseName());
        assertEquals("产成品仓", edited.get(1).getWarehouseName());

        ArgumentCaptor<WmsWarehouse> addCaptor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(warehouseService, times(2)).addWarehouse(addCaptor.capture());
        List<WmsWarehouse> added = addCaptor.getAllValues();
        assertEquals("CJ", added.get(0).getWarehouseCode());
        assertEquals(WmsConstants.WAREHOUSE_TYPE_NORMAL, added.get(0).getWarehouseType());
        assertEquals("TC", added.get(1).getWarehouseCode());

        // 二次执行：四个仓都已存在 → 不再新增
        WmsWarehouse cj = warehouse("wh-cj", "CJ", "初级产成品仓");
        WmsWarehouse tc = warehouse("wh-tc", "TC", "涂层产成品仓");
        when(warehouseMapper.selectOne(any())).thenReturn(lb, mj, cj, tc);
        service.initLocalArchives();
        verify(warehouseService, times(2)).addWarehouse(any());
    }

    private static WmsWarehouse warehouse(String id, String code, String name) {
        WmsWarehouse w = new WmsWarehouse();
        w.setId(id);
        w.setWarehouseCode(code);
        w.setWarehouseName(name);
        return w;
    }

    private static List<List<Object>> rows(List<Object> row) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }
}
