package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶销售订单读取：字段映射、按单号聚合、状态过滤、未启用分支。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsKingdeeSaleOrderReaderTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeSaleOrderReaderImpl reader;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        reader = new WmsKingdeeSaleOrderReaderImpl(formClient, properties);
    }

    @Test
    void queryByBillNo_aggregatesRowsIntoOrder() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt())).thenReturn(
                WmsKingdeeResult.ok("{}", List.of(
                        List.of("XSDD1", "C", "2026-10-20T00:00:00", "01.001", "湖南屹华科技有限公司",
                                "100", "备注", "WA.1", "物料A", 100.0, "2026-10-30T14:14:37", "zhi"),
                        List.of("XSDD1", "C", "2026-10-20T00:00:00", "01.001", "湖南屹华科技有限公司",
                                "100", "备注", "WA.2", "物料B", 5.0, "2026-10-28T00:00:00", "zhi")), null, null));

        WmsKingdeeSaleOrderQueryResult result = reader.queryByBillNo("XSDD1");

        assertTrue(result.isSuccess());
        assertEquals(1, result.getOrders().size());
        WmsKingdeeSaleOrder order = result.getOrders().get(0);
        assertEquals("XSDD1", order.getSalesOrderNo());
        assertEquals("C", order.getDocumentStatus());
        assertEquals("01.001", order.getCustomerCode());
        assertEquals("湖南屹华科技有限公司", order.getCustomerName());
        assertEquals(2, order.getLines().size());
        assertEquals("WA.2", order.getLines().get(1).getMaterialCode());
        assertEquals("zhi", order.getLines().get(1).getUnit());
    }

    @Test
    void queryByBillNo_emptyRows_returnsEmptyList() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
        assertTrue(reader.queryByBillNo("NOPE").getOrders().isEmpty());
    }

    @Test
    void queryApproved_filtersByStatusC() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
        reader.queryApproved(new Date(0), new Date(), 50);
        verify(formClient).executeBillQuery(anyString(), anyString(), contains("FDocumentStatus='C'"), anyInt());
    }

    @Test
    void queryByBillNo_queryFailure_returnsFail() {
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("金蝶不可达"));
        WmsKingdeeSaleOrderQueryResult result = reader.queryByBillNo("XSDD1");
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("金蝶不可达"));
    }

    @Test
    void disabled_returnsMockedEmpty() {
        properties.setEnabled(false);
        WmsKingdeeSaleOrderQueryResult result = reader.queryByBillNo("XSDD1");
        assertTrue(result.isSuccess());
        assertTrue(result.isMocked());
        assertTrue(result.getOrders().isEmpty());
    }
}
