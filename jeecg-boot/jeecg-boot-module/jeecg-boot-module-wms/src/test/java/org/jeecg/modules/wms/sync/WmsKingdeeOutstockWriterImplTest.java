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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SAL_OUTSTOCK 回写：单据类型、客户、仓库、批次与数量。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeeOutstockWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsKingdeeOutstockWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setOutStockBillType("XSCKD01_SYS");
        properties.setSaleOrgNumber("101");
        properties.setSettleCurrency("PRE001");
        properties.setDeliveryDeptId("201012");
        properties.setStockerId("1001");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setMaterialMap(Map.of("FG001", "WA.FG"));
        lookup = mock(WmsKingdeeLookupSupport.class);
        writer = new WmsKingdeeOutstockWriterImpl(formClient, properties, lookup);

        when(lookup.material("WA.FG", "101")).thenReturn(List.of(999L, "WA.FG", "成品", 888L, 1L, "101"));
        when(lookup.unitNumber(888L)).thenReturn("PCS");
        when(lookup.stockCode("101", "agile")).thenReturn("D.01");
        when(lookup.customerId("01.001", "101")).thenReturn(777L);
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.save(eq("SAL_OUTSTOCK"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "XSCK001", "id1"));
        when(formClient.submit(eq("SAL_OUTSTOCK"), eq(List.of("XSCK001"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("XS1").setSalesOrderNo("SO1")
                .setCustomerCode("01.001");
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail() {
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("FG001")
                .setBatchNo("B1").setActualQty(new BigDecimal("8"));
        detail.setId("d1");
        return detail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_usesOutStockBillTypeAndCustomer() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("SAL_OUTSTOCK"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertEquals("XS1", model.get("FBillNo"));
        assertEquals("XSCKD01_SYS", ((Map<?, ?>) model.get("FBillTypeID")).get("FNumber"));
        assertEquals(777L, ((Map<?, ?>) model.get("FCustomerID")).get("Id"));
        assertEquals("201012", ((Map<?, ?>) model.get("FDeliveryDeptID")).get("FNumber"));
        assertEquals("1001", ((Map<?, ?>) model.get("FStockerID")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.FG", ((Map<?, ?>) entry.get("FMaterialID")).get("FNumber"));
        assertEquals("D.01", ((Map<?, ?>) entry.get("FStockId")).get("FNumber"));
        assertEquals("B1", ((Map<?, ?>) entry.get("FLot")).get("FNumber"));
        assertEquals(0, ((BigDecimal) entry.get("FRealQty")).compareTo(new BigDecimal("8")));
        verify(formClient).submit("SAL_OUTSTOCK", List.of("XSCK001"));
    }

    @Test
    void write_missingDeptConfig_fails() {
        properties.setDeliveryDeptId("");

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未配置销售出库发货部门"));
    }
}
