package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SAL_SaleOrder 回写：必填字段、客户解析、幂等键。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeeSaleOrderWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsKingdeeSaleOrderWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setSaleOrderBillType("XSDD01_SYS");
        properties.setSaleOrgNumber("101");
        properties.setSalerId("S001");
        properties.setSettleCurrency("PRE001");
        properties.setMaterialMap(Map.of("PBC001", "WA.1"));
        lookup = mock(WmsKingdeeLookupSupport.class);
        writer = new WmsKingdeeSaleOrderWriterImpl(formClient, properties, lookup);

        when(lookup.material("WA.1", "101")).thenReturn(List.of(123L, "WA.1", "半成品", 456L, 1L, "101"));
        when(lookup.unitNumber(456L)).thenReturn("zhi");
        when(lookup.customerId("01.001", "101")).thenReturn(888L);
        when(formClient.executeBillQuery(eq("SAL_SaleOrder"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.save(eq("SAL_SaleOrder"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "XSDD001", "id1"));
        when(formClient.submit(eq("SAL_SaleOrder"), eq(List.of("XSDD001"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("XS1").setSalesOrderNo("SO1")
                .setCustomerCode("01.001").setDeliveryDate(new Date());
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail() {
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("PBC001")
                .setRequiredQty(new BigDecimal("5"));
        detail.setId("d1");
        return detail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_usesSaleBillTypeAndRequiredEntryFields() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("SAL_SaleOrder"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertEquals("SO1", model.get("FBillNo"));
        assertEquals("101", ((Map<?, ?>) model.get("FSaleOrgId")).get("FNumber"));
        assertEquals("XSDD01_SYS", ((Map<?, ?>) model.get("FBillTypeID")).get("FNumber"));
        assertEquals(888L, ((Map<?, ?>) model.get("FCustId")).get("Id"));
        assertEquals("S001", ((Map<?, ?>) model.get("FSalerId")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FSaleOrderEntry");
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.1", ((Map<?, ?>) entry.get("FMaterialId")).get("FNumber"));
        assertEquals("zhi", ((Map<?, ?>) entry.get("FUnitID")).get("FNumber"));
        assertEquals("zhi", ((Map<?, ?>) entry.get("FPriceUnitId")).get("FNumber"));
        assertEquals("zhi", ((Map<?, ?>) entry.get("FStockUnitID")).get("FNumber"));
        assertEquals("zhi", ((Map<?, ?>) entry.get("FOUTLMTUNIT")).get("FNumber"));
        assertEquals("101", ((Map<?, ?>) entry.get("FSettleOrgIds")).get("FNumber"));
        assertEquals("1", entry.get("FReserveType"));
        assertEquals(0, ((BigDecimal) entry.get("FQty")).compareTo(new BigDecimal("5")));

        List<Map<String, Object>> finance = (List<Map<String, Object>>) model.get("FSaleOrderFinance");
        assertEquals("PRE001", ((Map<?, ?>) finance.get(0).get("FSettleCurrId")).get("FNumber"));
        verify(formClient).submit("SAL_SaleOrder", List.of("XSDD001"));
    }

    @Test
    void write_customerNotFound_fails() {
        when(lookup.customerId("01.001", "101")).thenReturn(null);

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到客户编码"));
    }

    @Test
    void write_missingSalerConfig_fails() {
        properties.setSalerId("");

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置默认销售员"));
    }
}
