package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void dataCapLeavesRoomForTitleAndHeader() {
        // 2 行标题 + 1 行表头 + 数据 ≤ 1048576
        assertEquals(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX - 3,
                WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS);
    }
}
