package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶即时库存 STK_Inventory 读取：key 归一、稳定排序分页、失败抛异常、过滤条件。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsKingdeeInventoryReaderImplTest {

    private WmsKingdeeFormClient formClient;
    private IWmsKingdeeInventoryReader reader;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        reader = new WmsKingdeeInventoryReaderImpl(formClient);
    }

    @Test
    void queryInventory_singlePage_mapsKeysAndNormalizesBatch() {
        when(formClient.executeBillQuery(eq("STK_Inventory"), anyString(), anyString(), eq(500), eq(0), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        // 有批号
                        Arrays.asList("WA.001", "D.01", "L1", 10),
                        // 批号空串
                        Arrays.asList("WA.002", "D.02", "", 12.5),
                        // 批号 null
                        Arrays.asList("WA.003", "D.01", null, 3)
                ), null, null));

        Map<String, BigDecimal> map = reader.queryInventory(null);

        assertEquals(3, map.size());
        assertEquals(0, map.get("D.01|WA.001|L1").compareTo(new BigDecimal("10")));
        assertEquals(0, map.get("D.02|WA.002|").compareTo(new BigDecimal("12.5")));
        assertTrue(map.containsKey("D.01|WA.003|"));
    }

    @Test
    void queryInventory_pagesUntilShortPage() {
        List<List<Object>> firstPage = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            firstPage.add(Arrays.asList("M" + i, "D.01", "", i + 1));
        }
        List<List<Object>> secondPage = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            secondPage.add(Arrays.asList("N" + i, "D.02", "", i + 1));
        }
        when(formClient.executeBillQuery(eq("STK_Inventory"), anyString(), anyString(), eq(500), eq(0), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", firstPage, null, null));
        when(formClient.executeBillQuery(eq("STK_Inventory"), anyString(), anyString(), eq(500), eq(500), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", secondPage, null, null));

        Map<String, BigDecimal> map = reader.queryInventory(List.of("D.01", "D.02"));

        assertEquals(620, map.size());
        verify(formClient, times(2))
                .executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
        verify(formClient, times(1))
                .executeBillQuery(anyString(), anyString(), anyString(), eq(500), eq(0), anyString());
        verify(formClient, times(1))
                .executeBillQuery(anyString(), anyString(), anyString(), eq(500), eq(500), anyString());
    }

    @Test
    void queryInventory_queryFails_throwsRuntimeExceptionWithMessage() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.fail("元数据错误"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> reader.queryInventory(List.of("D.01")));

        assertEquals("元数据错误", e.getMessage());
        // 重试由上层负责，Reader 一次一调
        verify(formClient, times(1))
                .executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void queryInventory_buildsOrgWarehouseFilterAndStableOrder() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);

        reader.queryInventory(List.of("D.01", "D.02"));

        verify(formClient).executeBillQuery(
                eq("STK_Inventory"), fields.capture(), filter.capture(), eq(500), eq(0), order.capture());
        assertEquals("FMaterialId.FNumber,FStockId.FNumber,FLot.FNumber,FBaseQty", fields.getValue());
        assertTrue(filter.getValue().contains("FStockOrgId.FNumber='101'"), filter.getValue());
        assertTrue(filter.getValue().contains("FStockId.FNumber IN ('D.01','D.02')"), filter.getValue());
        // 翻页必须带稳定排序，否则会漏行/重复行；排序键须覆盖对账 key 与区分行的字段
        assertNotNull(order.getValue());
        assertFalse(order.getValue().isBlank(), "OrderString 不能为空");
        assertTrue(order.getValue().contains("FStockId.FNumber"), order.getValue());
        assertTrue(order.getValue().contains("FMaterialId.FNumber"), order.getValue());
        assertTrue(order.getValue().contains("FLot.FNumber"), order.getValue());
        assertTrue(order.getValue().contains("FStockLocId.FNumber"), order.getValue());
        assertTrue(order.getValue().contains("FStockStatusId.FNumber"), order.getValue());
    }

    @Test
    void queryInventory_emptyWarehouseCodes_onlyOrgFilter() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);

        reader.queryInventory(List.of());

        verify(formClient).executeBillQuery(
                eq("STK_Inventory"), anyString(), filter.capture(), eq(500), eq(0), anyString());
        assertEquals("FStockOrgId.FNumber='101'", filter.getValue());
    }

    @Test
    void queryInventory_duplicateKeysAreSummed() {
        // 同一仓库+物料+批次可能因库存状态不同返回多行；比对口径与 WMS 侧 SUM 一致
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        Arrays.asList("WA.001", "D.01", "L1", 10),
                        Arrays.asList("WA.001", "D.01", "L1", 5)
                ), null, null));

        Map<String, BigDecimal> map = reader.queryInventory(null);

        assertEquals(1, map.size());
        assertEquals(0, map.get("D.01|WA.001|L1").compareTo(new BigDecimal("15")));
        assertFalse(map.containsKey("D.01|WA.001|"));
    }

    @Test
    void queryInventory_nullOrBlankQty_skipsRowInsteadOfFailing() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        Arrays.asList("WA.001", "D.01", "L1", null),
                        Arrays.asList("WA.002", "D.01", "L1", ""),
                        Arrays.asList("WA.003", "D.01", "L1", "非法数量"),
                        Arrays.asList("WA.004", "D.01", "L1", 4)
                ), null, null));

        Map<String, BigDecimal> map = reader.queryInventory(null);

        assertEquals(1, map.size());
        assertEquals(0, map.get("D.01|WA.004|L1").compareTo(new BigDecimal("4")));
    }

    @Test
    void queryInventory_exceedsMaxPages_throwsInsteadOfLoopingForever() {
        List<List<Object>> fullPage = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            fullPage.add(Arrays.asList("M" + i, "D.01", "", i + 1));
        }
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", fullPage, null, null));

        RuntimeException e = assertThrows(RuntimeException.class, () -> reader.queryInventory(null));

        assertTrue(e.getMessage().contains(String.valueOf(WmsKingdeeInventoryReaderImpl.MAX_PAGES)),
                e.getMessage());
        verify(formClient, times(WmsKingdeeInventoryReaderImpl.MAX_PAGES))
                .executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString());
    }
}
