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
import static org.mockito.ArgumentMatchers.anyLong;
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
        properties.setEnabled(true);
        warehouseService = mock(IWmsWarehouseService.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsKingdeeWarehouseSyncServiceImpl(
                formClient, properties, warehouseService, warehouseMapper, syncLogService);
    }

    /**
     * 2026-10-28 起白名单为空 = **全量同步**本组织仓库（用户要求 WMS 库区与金蝶保持一致），
     * 不再是「白名单未配置就跳过」。
     */
    @Test
    void shouldSyncWholeOrgWhenWhitelistBlank() {
        properties.getMasterData().setOrgId("101");
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", Collections.emptyList(), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("BD_STOCK"), anyString(), filterCaptor.capture(), anyInt(), anyInt());
        String filter = filterCaptor.getValue();
        assertTrue(filter.contains("FUseOrgId.FNumber='101'"), filter);
        // 全量时不能带 in(...) 白名单条件
        assertTrue(!filter.contains("FNumber in"), filter);
        assertEquals(0, vo.getFetched());
    }

    @Test
    void shouldFilterByOrgAndWhitelist() {
        properties.getMasterData().setOrgId("101");
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", Collections.emptyList(), null, null));

        service.syncStock();

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("BD_STOCK"), anyString(), filterCaptor.capture(), anyInt(), anyInt());
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
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
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
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
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

    /**
     * 2026-10-28 起「初始化本地档案」废弃：WMS 仓库编码必须与金蝶 1:1，
     * 自建 CJ/TC、改 LB/MJ 会造出金蝶里不存在的编码。
     */
    @Test
    void initLocalArchivesIsDeprecatedNoOp() {
        service.initLocalArchives();

        verify(warehouseService, never()).addWarehouse(any());
        verify(warehouseService, never()).editWarehouse(any());
    }

    // ------------------------------------------------ 审查修复（Critical/Important）

    @Test
    void shouldSkipWhenGlobalKingdeeSwitchOff() {
        properties.setEnabled(false);
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(0, vo.getFetched());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldSkipWhenOrgIdBlank() {
        properties.getMasterData().setOrgId("  ");
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(0, vo.getFetched());
        assertTrue(vo.getMessage().contains("组织"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldTreatBlankWhitelistEntriesAsFullSync() {
        properties.getMasterData().setOrgId("101");
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of(" ", "")));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", Collections.emptyList(), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncStock();

        assertEquals(0, vo.getFetched());
        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("BD_STOCK"), anyString(), filterCaptor.capture(), anyInt(), anyInt());
        assertTrue(!filterCaptor.getValue().contains("FNumber in"), filterCaptor.getValue());
    }

    @Test
    void shouldMarkExternalWarehouse() {
        properties.getMasterData().setOrgId("101");
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        rows(List.of("CK007", "宜昌永鑫精工科技股份有限公司vmi")), null, null));

        service.syncStock();

        ArgumentCaptor<WmsWarehouse> captor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(warehouseService).addWarehouse(captor.capture());
        // CK007 是 VMI 外部仓：同步进来但标外部仓，后续不同步库存、不作入库目标
        assertEquals("外部仓", captor.getValue().getWarehouseType());
    }

    @Test
    void shouldRejectMappingWhenErpCodeAlreadyTakenByAnotherWarehouse() {
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));
        properties.getMasterData().getStockCodeMap().put("X.2.4", "TC");

        WmsWarehouse mapped = warehouse("wh-tc", "TC", "涂层产成品仓");
        WmsWarehouse squatter = warehouse("wh-x24", "X.2.4", "涂层产成品仓");
        when(warehouseMapper.selectOne(any())).thenReturn(mapped, squatter);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("X.2.4", "涂层产成品仓")), null, null));

        RuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.syncStock());
        assertTrue(e.getMessage().contains("X.2.4"), e.getMessage());
        assertTrue(e.getMessage().contains("占用"), e.getMessage());
    }

    @Test
    void shouldWriteSyncLogWithProjectVocabulary() {
        properties.getMasterData().setStockWhitelist(new ArrayList<>(List.of("X.2.4")));
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("X.2.4", "涂层产成品仓")), null, null));

        service.syncStock();

        verify(syncLogService).logSync(eq("ERP_TO_WMS"), eq("WAREHOUSE"), anyString(),
                eq("KINGDEE"), eq(true), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    void shouldLogSkippedSync() {
        properties.setEnabled(false);
        service.syncStock();

        verify(syncLogService).logSync(eq("ERP_TO_WMS"), eq("WAREHOUSE"), anyString(),
                eq("KINGDEE"), eq(true), anyInt(), any(), any(), any(), anyLong());
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
