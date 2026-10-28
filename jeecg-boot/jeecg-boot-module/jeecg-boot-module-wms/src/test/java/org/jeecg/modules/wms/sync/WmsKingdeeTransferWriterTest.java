package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.mapper.WmsTransferOrderMapper;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶直接调拨单回写：表头/明细字段、幂等、绝不 Audit 的边界。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsKingdeeTransferWriterTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsTransferOrderMapper transferMapper;
    private WmsKingdeeTransferWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setDefaultOrgNumber("101");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setTransferStockerNumber("0050");
        properties.getStockCode().put("MJ", "D.01");
        properties.getStockCode().put("LB", "D.02");
        transferMapper = mock(WmsTransferOrderMapper.class);
        writer = new WmsKingdeeTransferWriterImpl(formClient, properties, transferMapper);
    }

    private WmsTransferOrder order() {
        WmsTransferOrder order = new WmsTransferOrder();
        order.setId("1");
        order.setTransferNo("DB1001");
        order.setErpOrgCode("101");
        order.setOutWarehouseCode("MJ");
        order.setInWarehouseCode("LB");
        return order;
    }

    private WmsTransferDetail detail() {
        return new WmsTransferDetail()
                .setMaterialCode("E.008.0065")
                .setBatchNo("JD")
                .setUnit("PCS")
                .setTransferQty(new BigDecimal("3"))
                .setActualQty(new BigDecimal("2"));
    }

    @Test
    void disabled_returnsMockedSuccess() {
        properties.setEnabled(false);
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertTrue(result.isSuccess());
        assertTrue(result.isMocked());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void existingSubmittedBill_isIdempotentSuccess() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(List.of("DB1001", "B")), null, null));
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertTrue(result.isSuccess());
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).submit(anyString(), anyList());
    }

    @Test
    void existingDraftBill_onlySubmits() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(List.of("DB1001", "A")), null, null));
        when(formClient.submit(anyString(), anyList()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), "DB1001", "1"));
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertTrue(result.isSuccess());
        verify(formClient, never()).save(anyString(), any());
        verify(formClient).submit(eq("STK_TransferDirect"), anyList());
    }

    /** 依次返回：查单号（空）→ 物料（含基本单位内码）→ 单位编号。 */
    private void stubEmptyMaterialUnitOnSave() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null),
                        WmsKingdeeResult.ok("{}", List.of(List.of("M1", "E.008.0065", "100", "101")), null, null),
                        WmsKingdeeResult.ok("{}", List.of(List.of("100", "PCS-KD")), null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newBill_savesHeaderAndEntriesWithoutStockLoc() {
        stubEmptyMaterialUnitOnSave();
        when(formClient.save(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", List.of(), "DB1001", "1"));
        when(formClient.submit(anyString(), anyList())).thenReturn(WmsKingdeeResult.ok("{}", List.of(), "DB1001", "1"));

        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertTrue(result.isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("STK_TransferDirect"), captor.capture());
        Map<String, Object> model = captor.getValue();
        // 单号以金蝶为准：不再传 FBillNo
        assertFalse(model.containsKey("FBillNo"));
        assertEquals("ZJDB01_SYS", ((Map<?, ?>) model.get("FBillTypeID")).get("FNumber"));
        assertEquals("101", ((Map<?, ?>) model.get("FStockOrgId")).get("FNumber"));
        assertEquals("101", ((Map<?, ?>) model.get("FStockOutOrgId")).get("FNumber"));
        assertEquals("InnerOrgTransfer", model.get("FTransferBizType"));
        assertEquals("GENERAL", model.get("FTransferDirect"));
        assertEquals("0050", ((Map<?, ?>) model.get("FStockerId")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FBillEntry");
        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        assertEquals("E.008.0065", ((Map<?, ?>) entry.get("FMaterialId")).get("FNumber"));
        assertEquals("PCS-KD", ((Map<?, ?>) entry.get("FUnitID")).get("FNumber"));
        assertEquals("D.01", ((Map<?, ?>) entry.get("FSrcStockId")).get("FNumber"));
        assertEquals("D.02", ((Map<?, ?>) entry.get("FDestStockId")).get("FNumber"));
        // 实发数量优先
        assertEquals(new BigDecimal("2"), entry.get("FQty"));
        // 101 不启用仓位：绝不传仓位字段
        assertFalse(entry.containsKey("FSrcStockLocId"));
        assertFalse(entry.containsKey("FDestStockLocId"));
        verify(formClient).submit(eq("STK_TransferDirect"), anyList());
    }

    @Test
    void missingStockCodeConfig_failsWithMessage() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
        properties.getStockCode().clear();
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置金蝶仓库编码"));
    }

    @Test
    void saveFailure_returnsMessage() {
        stubEmptyMaterialUnitOnSave();
        when(formClient.save(anyString(), any())).thenReturn(WmsKingdeeResult.fail("仓管员必填"));
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("保存失败"));
    }

    @Test
    void submitFailure_returnsMessage() {
        stubEmptyMaterialUnitOnSave();
        when(formClient.save(anyString(), any())).thenReturn(WmsKingdeeResult.ok("{}", List.of(), "DB1001", "1"));
        when(formClient.submit(anyString(), anyList())).thenReturn(WmsKingdeeResult.fail("工作流异常"));
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("提交失败"));
    }

    @Test
    void queryStatus_returnsDocumentStatus() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(List.of("DB1001", "D")), null, null));
        WmsKingdeeTransferStatus status = writer.queryStatus("DB1001");
        assertTrue(status.isSuccess());
        assertEquals("D", status.getDocumentStatus());
    }

    @Test
    void queryStatus_missingBill_returnsNullStatus() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
        WmsKingdeeTransferStatus status = writer.queryStatus("DB1001");
        assertTrue(status.isSuccess());
        assertEquals(null, status.getDocumentStatus());
    }

    @Test
    void queryStatus_disabledIsMocked() {
        properties.setEnabled(false);
        WmsKingdeeTransferStatus status = writer.queryStatus("DB1001");
        assertTrue(status.isSuccess());
        assertTrue(status.isMocked());
    }

    @Test
    void queryStatus_simulateSwitch_returnsConfiguredStatusWithoutCall() {
        org.springframework.test.util.ReflectionTestUtils.setField(writer, "simulateErpStatus", "C");
        WmsKingdeeTransferStatus status = writer.queryStatus("DB1001");
        assertTrue(status.isSuccess());
        assertEquals("C", status.getDocumentStatus());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void saveAndSubmit_simulateC_isIdempotentWithoutSaveCall() {
        org.springframework.test.util.ReflectionTestUtils.setField(writer, "simulateErpStatus", "C");
        WmsOrderSyncResult result = writer.saveAndSubmit(order(), List.of(detail()));
        assertTrue(result.isSuccess());
        verify(formClient, never()).save(anyString(), any());
    }
}
