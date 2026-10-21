package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: PRD_PickMtrl 回写：组织跟随工单、必填字段、幂等、mock。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeePickWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsKingdeePickWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setPickBillType("SCLLD01_SYS");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setStockStatusId("KCZT01_SYS");
        properties.setMaterialMap(Map.of("PBC001", "WA.1"));
        lookup = mock(WmsKingdeeLookupSupport.class);
        writer = new WmsKingdeePickWriterImpl(formClient, properties, lookup);

        when(lookup.orgNumberForWorkOrder("MO1")).thenReturn("100");
        when(lookup.material("WA.1", "100")).thenReturn(List.of(123L, "WA.1", "半成品", 456L, 1L, "100"));
        when(lookup.unitNumber(456L)).thenReturn("zhi");
        when(lookup.stockCode("100", "stereo")).thenReturn("02.002");
        when(lookup.stockLocId("02.002")).thenReturn(null);
        when(formClient.executeBillQuery(eq("PRD_PickMtrl"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.save(eq("PRD_PickMtrl"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCLL001", "id1"));
        when(formClient.submit(eq("PRD_PickMtrl"), eq(List.of("SCLL001"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("LL1").setWorkOrderNo("MO1");
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail() {
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("PBC001")
                .setBatchNo("B1").setActualQty(new BigDecimal("10"));
        detail.setId("d1");
        return detail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_buildsRequiredFields() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("PRD_PickMtrl"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertEquals("LL1", model.get("FBillNo"));
        assertEquals("100", ((Map<?, ?>) model.get("FPrdOrgId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) model.get("FStockOrgId")).get("FNumber"));
        assertEquals("SCLLD01_SYS", ((Map<?, ?>) model.get("FBillType")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.1", ((Map<?, ?>) entry.get("FMaterialId")).get("FNumber"));
        assertEquals("02.002", ((Map<?, ?>) entry.get("FStockId")).get("FNumber"));
        assertEquals("MO1", entry.get("FMoBillNo"));
        assertEquals("100", ((Map<?, ?>) entry.get("FParentOwnerId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) entry.get("FKeeperId")).get("FNumber"));
        assertEquals("KCZT01_SYS", ((Map<?, ?>) entry.get("FStockStatusId")).get("FNumber"));
        assertEquals(0, ((BigDecimal) entry.get("FRealQty")).compareTo(new BigDecimal("10")));
        assertEquals("B1", ((Map<?, ?>) entry.get("FLot")).get("FNumber"));
        assertFalse(((List<?>) model.get("FSerialSubEntity")).isEmpty());
        verify(formClient).submit("PRD_PickMtrl", List.of("SCLL001"));
    }

    @Test
    void write_existingAudited_isIdempotent() {
        when(formClient.executeBillQuery(eq("PRD_PickMtrl"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("LL1", "C")), null, null));

        assertTrue(writer.write(order(), List.of(detail())).isSuccess());
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).submit(anyString(), any());
    }

    @Test
    void write_existingDraft_submits() {
        when(formClient.executeBillQuery(eq("PRD_PickMtrl"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("LL1", "A")), null, null));
        when(formClient.submit("PRD_PickMtrl", List.of("LL1")))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        assertTrue(writer.write(order(), List.of(detail())).isSuccess());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_moNotFound_fails() {
        when(lookup.orgNumberForWorkOrder("MO1")).thenReturn(null);

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到生产订单"));
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_kingdeeDisabled_returnsMock() {
        properties.setEnabled(false);

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertTrue(result.isSuccess());
        assertTrue(result.isMocked());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }
}
