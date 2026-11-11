package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.sync.IWmsKingdeeInventoryReader;
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
    private IWmsKingdeeInventoryReader inventoryReader;
    private WmsStocktakeErpWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        materialMapper = mock(org.jeecg.modules.wms.mapper.WmsMaterialMapper.class);
        inventoryReader = mock(IWmsKingdeeInventoryReader.class);
        // 默认金蝶有货，避免前置校验干扰原有断言；验证拦截时在用例内覆盖
        when(inventoryReader.queryInventory(any())).thenReturn(Map.of(
                "D.01|MC-m1|B1", new BigDecimal("100"),
                "D.01|MC-m2|B1", new BigDecimal("100"),
                "D.01|MC-m3|B1", new BigDecimal("100"),
                "D.02|MC-m2|B1", new BigDecimal("100")));
        writer = new WmsStocktakeErpWriterImpl(properties, formClient, detailMapper, orderMapper,
                materialMapper, inventoryReader);
        // 公共桩：保存成功后要 Submit（不提交单据停在 A），默认成功
        when(formClient.submit(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
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
     * 盘盈/盘亏分录都要带仓库（2026-11-09 二次实测更正）：盘盈缺 FStockId 报「必填项」；
     * 盘亏缺 FStockId 虽能存，但金蝶会按**物料默认仓库**落库（实测写成 Y.05 而非盘点的 D.02），
     * 审核时按错仓扣库存必报负库存——所以盘亏侧也必须显式传。
     * 库存状态 FStockStatusId 仍只盘盈需要。
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
        assertEquals(Map.of("FNumber", "D.01"), lossEntries.get(0).get("FStockId"),
                "盘亏侧必须显式传仓库，否则金蝶按物料默认仓落库");
        assertFalse(lossEntries.get(0).containsKey("FStockStatusId"), "盘亏侧不需要库存状态");
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

    /** 保存成功后必须 Submit（A→B）——不提交单据停在 A，金蝶前台无法直接审核。 */
    @Test
    void savedBillIsSubmitted() {
        properties.setAllowHttp(true);
        properties.getStocktake().setStockCode("D.02");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-2", "m2", "10", "7")));
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTCK000001", "1"));
        when(formClient.submit(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTCK000001", "1"));

        writer.writeLoss(order());

        verify(formClient).submit("STK_MisDelivery", List.of("QTCK000001"));
    }

    /** 技术债 §5-15b：金蝶目标仓没有足够即时库存时，不得开出必被审核拒绝的 QTCK。 */
    @Test
    void lossIsBlockedWhenKingdeeInventoryInsufficient() {
        properties.getStocktake().setStockCode("D.02");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-2", "m2", "10", "7")));
        when(inventoryReader.queryInventory(any()))
                .thenReturn(Map.of("D.02|MC-m2|B1", new BigDecimal("1")));

        WmsOrderSyncResult loss = writer.writeLoss(order());

        assertFalse(loss.isSuccess());
        assertTrue(loss.getErrorMessage().contains("前置校验"), loss.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /** D7：保存成功后金蝶单号必须回存（幂等键/对账键）。 */
    @Test
    void gain_persistsReturnedKingdeeBillNo() {
        properties.getStocktake().setStockCode("D.01");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-1", "m1", "10", "12")));
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTRK900001", "1"));

        writer.writeGain(order());

        ArgumentCaptor<WmsStocktakeOrder> captor = ArgumentCaptor.forClass(WmsStocktakeOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals("QTRK900001", captor.getValue().getErpBillNo());
    }

    /** D7：盘盈/盘亏两张单号分号拼接、去重合并。 */
    @Test
    void writeback_mergesExistingErpBillNoWithoutDuplicate() {
        properties.getStocktake().setStockCode("D.01");
        WmsStocktakeOrder st = order();
        st.setErpBillNo("QTRK900001");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-2", "m2", "10", "7")));
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTCK900002", "1"));

        writer.writeLoss(st);

        ArgumentCaptor<WmsStocktakeOrder> captor = ArgumentCaptor.forClass(WmsStocktakeOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals("QTRK900001;QTCK900002", captor.getValue().getErpBillNo());
    }

    /** D7：回存单号与已有完全一致时不重复写库。 */
    @Test
    void writeback_sameBillNo_skipsRedundantUpdate() {
        properties.getStocktake().setStockCode("D.01");
        WmsStocktakeOrder st = order();
        st.setErpBillNo("QTCK900002");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-2", "m2", "10", "7")));
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTCK900002", "1"));

        writer.writeLoss(st);

        verify(orderMapper, never()).updateById(any(WmsStocktakeOrder.class));
    }

    /** 查询故障不等于库存不足：金蝶不可用时放行，交给原有 Save 失败链路。 */
    @Test
    void lossProceedsWhenInventoryQueryFails() {
        properties.getStocktake().setStockCode("D.02");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("loc-2", "m2", "10", "7")));
        when(inventoryReader.queryInventory(any())).thenThrow(new RuntimeException("金蝶超时"));
        when(formClient.save(anyString(), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "QTCK1", "1"));

        WmsOrderSyncResult loss = writer.writeLoss(order());

        assertTrue(loss.isSuccess(), loss.getErrorMessage());
    }
}
