package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点差异回写金蝶——盘盈 QTRK / 盘亏 QTCK，两侧独立、未配置明确报错（spec §5.3 / D13）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeErpWriterTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsStocktakeDetailMapper detailMapper;
    private WmsStocktakeOrderMapper orderMapper;
    private org.jeecg.modules.wms.mapper.WmsMaterialMapper materialMapper;
    private WmsStocktakeErpWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        materialMapper = mock(org.jeecg.modules.wms.mapper.WmsMaterialMapper.class);
        writer = new WmsStocktakeErpWriterImpl(properties, formClient, detailMapper, orderMapper, materialMapper);
    }

    private WmsStocktakeOrder order() {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-1");
        order.setStocktakeNo("PD202610260001");
        return order;
    }

    @Test
    void shouldFailExplicitlyWhenStockCodeMissing() {
        WmsOrderSyncResult gain = writer.writeGain(order());
        WmsOrderSyncResult loss = writer.writeLoss(order());

        assertFalse(gain.isSuccess());
        assertTrue(gain.getErrorMessage().contains("未配置"), gain.getErrorMessage());
        assertFalse(loss.isSuccess());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void gainWritesQtrkAndLossWritesQtckIndependently() {
        properties.setAllowHttp(true);
        properties.getStocktake().setStockCode("D.01");
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTRK000001", "1"));
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("loc-1", "m1", "10", "12"),   // 盈 2
                detail("loc-2", "m2", "10", "7")));  // 亏 3

        WmsOrderSyncResult gain = writer.writeGain(order());
        WmsOrderSyncResult loss = writer.writeLoss(order());

        assertTrue(gain.isSuccess());
        assertTrue(loss.isSuccess());
        ArgumentCaptor<String> formCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient, org.mockito.Mockito.times(2)).save(formCaptor.capture(), any());
        assertEquals("STK_Miscellaneous", formCaptor.getAllValues().get(0));
        assertEquals("STK_MisDelivery", formCaptor.getAllValues().get(1));
    }

    @Test
    void halfFailureShouldBeIndependent() {
        properties.getStocktake().setStockCode("D.01");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("loc-1", "m1", "10", "12"),   // 盈 2
                detail("loc-2", "m2", "10", "7")));  // 亏 3
        when(formClient.save(eq("STK_Miscellaneous"), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTRK1", "1"));
        when(formClient.save(eq("STK_MisDelivery"), any()))
                .thenReturn(WmsKingdeeResult.fail("金蝶接口失败"));

        WmsOrderSyncResult gain = writer.writeGain(order());
        WmsOrderSyncResult loss = writer.writeLoss(order());

        assertTrue(gain.isSuccess());
        assertFalse(loss.isSuccess());
        // 两侧独立：盘盈成功不被盘亏失败拖累
        verify(formClient, org.mockito.Mockito.times(2)).save(anyString(), any());
    }

    private static WmsStocktakeDetail detail(String loc, String mat, String book, String actual) {
        WmsStocktakeDetail detail = new WmsStocktakeDetail();
        detail.setStocktakeId("st-1");
        detail.setLocationId(loc);
        detail.setLocationCode("LC-" + loc);
        detail.setMaterialId(mat);
        detail.setMaterialCode("MC-" + mat);
        detail.setBatchNo("B1");
        detail.setBookQty(new BigDecimal(book));
        detail.setActualQty(new BigDecimal(actual));
        return detail;
    }

    /**
     * 盘盈分录必须带收货仓库与库存状态（2026-11-09 真账套：缺这两项报「分录第1行字段"收货仓库"/"库存状态"是必填项」，
     * 开出 QTRK10126110002）；盘亏侧 STK_MisDelivery 不带也能存，别顺手加过去改坏了既有报文。
     */
    @Test
    void gainEntryCarriesStockAndStatusRequiredByQtrk() {
        properties.getStocktake().setStockCode("D.01");
        properties.setStockStatusId("KCZT01_SYS");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("loc-1", "m1", "10", "12"),   // 盈 2
                detail("loc-2", "m2", "10", "7")));  // 亏 3
        when(formClient.save(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTRK000001", "1"));

        writer.writeGain(order());
        writer.writeLoss(order());

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        verify(formClient, org.mockito.Mockito.times(2)).save(anyString(), modelCaptor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gainEntries =
                (List<Map<String, Object>>) modelCaptor.getAllValues().get(0).get("FEntity");
        assertEquals(Map.of("FNumber", "D.01"), gainEntries.get(0).get("FStockId"));
        assertEquals(Map.of("FNumber", "KCZT01_SYS"), gainEntries.get(0).get("FStockStatusId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lossEntries =
                (List<Map<String, Object>>) modelCaptor.getAllValues().get(1).get("FEntity");
        assertFalse(lossEntries.get(0).containsKey("FStockId"), "盘亏侧不要加 FStockId");
        assertFalse(lossEntries.get(0).containsKey("FStockStatusId"), "盘亏侧不要加 FStockStatusId");
    }

    @Test
    void zeroDiffRowsAreExcludedFromBothSides() {
        properties.getStocktake().setStockCode("D.01");
        when(detailMapper.selectList(any())).thenReturn(List.of(
                detail("loc-1", "m1", "10", "12"),  // 盈 2
                detail("loc-2", "m2", "10", "10"),  // 零差异
                detail("loc-3", "m3", "10", "7")));  // 亏 3
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "B1", "1"));

        writer.writeGain(order());
        writer.writeLoss(order());

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        verify(formClient, org.mockito.Mockito.times(2)).save(anyString(), modelCaptor.capture());
        for (Map<String, Object> model : modelCaptor.getAllValues()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
            assertEquals(1, entries.size(), "零差异行不得出现在任何一侧：" + entries);
        }
    }
}
