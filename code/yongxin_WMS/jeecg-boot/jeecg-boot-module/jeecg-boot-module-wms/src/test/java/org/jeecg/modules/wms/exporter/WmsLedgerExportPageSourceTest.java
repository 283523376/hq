package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出游标分批数据源（spec 2026-10-29 §6.2）。
 */
class WmsLedgerExportPageSourceTest {

    private WmsInventoryMapper mapper;
    private WmsInventoryLedgerQueryDTO query;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsInventoryMapper.class);
        query = new WmsInventoryLedgerQueryDTO();
    }

    private WmsInventoryLedgerVO row(String materialId, String locationId, String batchNo, String containerCode) {
        WmsInventoryLedgerVO vo = new WmsInventoryLedgerVO();
        vo.setMaterialId(materialId);
        vo.setLocationId(locationId);
        vo.setBatchNo(batchNo);
        vo.setContainerCode(containerCode);
        // 唯一键自 V2.4 起是六列：游标必须把这四列之外的 ownership_type/consignor_id 一起带上，
        // 否则「同物料/库位/批次/容器、不同受托方」的两行会被跳过
        vo.setOwnershipType("OWNED");
        vo.setConsignorId("");
        return vo;
    }

    @Test
    void firstCallPassesNullCursorAndReturnsBatch() {
        when(mapper.selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(Arrays.asList(row("m1", "l1", "b1", "c1"), row("m2", "l2", "b2", "c2")));
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(mapper, query, 2, 10);

        List<Object> batch = source.selectListForExcelExport(null, 1);

        assertEquals(2, batch.size());
        verify(mapper).selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2));
    }

    @Test
    void secondCallPassesLastRowAsCursor() {
        WmsInventoryLedgerVO last = row("m2", "l2", "b2", "c2");
        when(mapper.selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(Arrays.asList(row("m1", "l1", "b1", "c1"), last));
        when(mapper.selectLedgerCursor(any(), eq("m2"), eq("l2"), eq("b2"), eq("c2"), eq("OWNED"), eq(""), eq(2)))
                .thenReturn(Collections.emptyList());
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(mapper, query, 2, 10);

        source.selectListForExcelExport(null, 1);
        source.selectListForExcelExport(null, 2);

        verify(mapper).selectLedgerCursor(any(), eq("m2"), eq("l2"), eq("b2"), eq("c2"), eq("OWNED"), eq(""), eq(2));
    }

    @Test
    void emptyBatchEndsIteration() {
        when(mapper.selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(Collections.emptyList());
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(mapper, query, 2, 10);

        assertNull(source.selectListForExcelExport(null, 1), "取空即结束，返回 null 让 AutoPoi 停止");
        assertEquals(0L, source.getExportedCount());
    }

    @Test
    void throwsWhenExceedingMaxRows() {
        when(mapper.selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(Arrays.asList(row("m1", "l1", "b1", "c1"), row("m2", "l2", "b2", "c2")));
        when(mapper.selectLedgerCursor(any(), eq("m2"), eq("l2"), eq("b2"), eq("c2"), eq("OWNED"), eq(""), eq(2)))
                .thenReturn(Arrays.asList(row("m3", "l3", "b3", "c3"), row("m4", "l4", "b4", "c4")));
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(mapper, query, 2, 3);

        source.selectListForExcelExport(null, 1);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> source.selectListForExcelExport(null, 2));

        assertTrue(ex.getMessage().contains("Excel 单表上限"), "实际文案: " + ex.getMessage());
    }

    @Test
    void exportedCountAccumulates() {
        when(mapper.selectLedgerCursor(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(2)))
                .thenReturn(Arrays.asList(row("m1", "l1", "b1", "c1"), row("m2", "l2", "b2", "c2")));
        when(mapper.selectLedgerCursor(any(), eq("m2"), eq("l2"), eq("b2"), eq("c2"), eq("OWNED"), eq(""), eq(2)))
                .thenReturn(Arrays.asList(row("m3", "l3", "b3", "c3"), row("m4", "l4", "b4", "c4")));
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(mapper, query, 2, 10);

        source.selectListForExcelExport(null, 1);
        source.selectListForExcelExport(null, 2);

        assertEquals(4L, source.getExportedCount());
    }
}
