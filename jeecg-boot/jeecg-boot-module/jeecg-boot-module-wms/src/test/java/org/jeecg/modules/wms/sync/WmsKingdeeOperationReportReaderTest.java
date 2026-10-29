package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工序汇报增量口径：按 FModifyDate（最后修改时间）过滤 + 稳定排序（spec 2026-10-29）。
 */
class WmsKingdeeOperationReportReaderTest {

    private WmsKingdeeFormClient client;
    private WmsKingdeeOperationReportReader reader;

    private void setUpReader() {
        client = mock(WmsKingdeeFormClient.class);
        WmsKingdeeProperties properties = mock(WmsKingdeeProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getWorkOrderOrgNumber()).thenReturn("101");
        when(client.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("{}", List.of(), null, null));
        reader = new WmsKingdeeOperationReportReader(client, properties);
    }

    @Test
    void filtersByModifyDateNotBillDate() {
        setUpReader();

        reader.fetch("2026-10-29 00:00:00", "2026-10-29 23:59:59", 100);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(client).executeBillQuery(eq(WmsKingdeeOperationReportReader.FORM_ID), anyString(),
                filter.capture(), anyInt(), anyInt(), anyString());
        assertTrue(filter.getValue().contains("FModifyDate>='2026-10-29 00:00:00'"),
                "必须按 FModifyDate 过滤，实际: " + filter.getValue());
        assertTrue(filter.getValue().contains("FModifyDate<='2026-10-29 23:59:59'"),
                "实际: " + filter.getValue());
        assertFalse(filter.getValue().contains("FDate>="),
                "不能再按业务日期 FDate 过滤（迟审单据会永久丢失），实际: " + filter.getValue());
        assertTrue(filter.getValue().contains("FDocumentStatus='C'"));
        assertTrue(filter.getValue().contains("FPrdOrgId.FNumber='101'"));
    }

    @Test
    void dateOnlyBoundsAreExpandedToWholeDay() {
        setUpReader();

        // 手工按钮传的是 yyyy-MM-dd；FModifyDate 是 datetime，
        // 不补时间的话 `<= '2026-10-29'` 只到当天 00:00:00，整天都会漏。
        reader.fetch("2026-10-29", "2026-10-29", 100);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(client).executeBillQuery(anyString(), anyString(), filter.capture(), anyInt(), anyInt(), anyString());
        assertTrue(filter.getValue().contains("FModifyDate>='2026-10-29 00:00:00'"),
                "实际: " + filter.getValue());
        assertTrue(filter.getValue().contains("FModifyDate<='2026-10-29 23:59:59'"),
                "实际: " + filter.getValue());
    }

    @Test
    void usesStableOrderForPaging() {
        setUpReader();

        reader.fetch("2026-10-29 00:00:00", "2026-10-29 23:59:59", 100);

        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        verify(client).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt(), order.capture());
        String o = order.getValue();
        assertTrue(o.contains("FModifyDate"), "排序必须含 FModifyDate，实际: " + o);
        assertTrue(o.contains("FBillNo"), "排序必须含唯一列，否则翻页会重复/漏行，实际: " + o);
    }
}
