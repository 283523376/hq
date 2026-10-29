package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出参数：必须禁用 AutoPoi 的默认滚动分页（USE_SXSSF_LIMIT=100000 会把台账拆成没有表头的 Sheet1/2/3）。
 */
class WmsLedgerExportParamsTest {

    @Test
    void sheetMaxNumRaisedAboveDataCap() {
        ExportParams params = WmsLedgerExportService.buildExportParams("标题");
        assertEquals(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX, params.getMaxNum(),
                "必须抬到 xlsx 物理上限，禁用 10 万行滚动分页");
        assertEquals(1048576, params.getMaxNum());
    }

    @Test
    void exportPinsStockAgeReferenceDate() throws Exception {
        // 库龄用 DATEDIFF(NOW(), ...) 的话，跨午夜的长导出同一份文件里前后批次会差一天；
        // 导出必须把「快照日」钉死，让整份文件的库龄口径一致。
        WmsInventoryMapper mapper = mock(WmsInventoryMapper.class);
        when(mapper.selectLedgerCursor(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.Collections.emptyList());
        WmsExcelWriter writer = mock(WmsExcelWriter.class);
        WmsInventoryLedgerQueryDTO query = new WmsInventoryLedgerQueryDTO();

        new WmsLedgerExportService(mapper, writer).generate(query, new File("target/not-written.xlsx"));

        assertNotNull(query.getAsOfDate(), "导出前必须把 asOfDate 钉成快照时间");
    }

    @Test
    void dataCapLeavesRoomForTitleAndHeader() {
        // 2 行标题 + 1 行表头 + 数据 ≤ 1048576
        assertEquals(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX - 3,
                WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS);
    }
}
