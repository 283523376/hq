package org.jeecg.modules.wms.exporter;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.jeecgframework.poi.handler.inter.IExcelExportServer;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 库存台账导出的分批数据源（KC-004）。
 *
 * <p>AutoPoi 的 {@code exportBigExcel} 会反复回调 {@link #selectListForExcelExport}，
 * 直到返回 null 或空列表。这里用「上一批末行」当游标，按 `wms_inventory` 唯一键的六列继续往下取，
 * **不用 OFFSET**——几十万行时 `LIMIT 5000 OFFSET 350000` 会让 MySQL 从头扫 35 万行。
 *
 * <p>回调是单线程顺序调用的，所以游标状态不需要加锁。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
public class WmsLedgerExportPageSource implements IExcelExportServer {

    private final WmsInventoryMapper inventoryMapper;
    private final WmsInventoryLedgerQueryDTO query;
    private final int batchSize;
    private final int maxRows;

    /** 上一批的最后一行；null 表示还没取过。 */
    private WmsInventoryLedgerVO lastRow;
    /** 累计已产出的行数。 */
    private long exported;

    public WmsLedgerExportPageSource(WmsInventoryMapper inventoryMapper,
                                     WmsInventoryLedgerQueryDTO query,
                                     int batchSize,
                                     int maxRows) {
        this.inventoryMapper = inventoryMapper;
        this.query = query;
        this.batchSize = batchSize;
        this.maxRows = maxRows;
    }

    @Override
    public List<Object> selectListForExcelExport(Object queryParams, int page) {
        List<WmsInventoryLedgerVO> rows = inventoryMapper.selectLedgerCursor(
                query,
                lastRow == null ? null : lastRow.getMaterialId(),
                lastRow == null ? null : lastRow.getLocationId(),
                lastRow == null ? null : lastRow.getBatchNo(),
                lastRow == null ? null : lastRow.getContainerCode(),
                lastRow == null ? null : lastRow.getOwnershipType(),
                lastRow == null ? null : lastRow.getConsignorId(),
                batchSize);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // 行数守卫必须在返回之前：超限时不能先把行塞进工作簿再抛错
        if (exported + rows.size() > maxRows) {
            throw new IllegalStateException("导出行数超过 Excel 单表上限（" + maxRows
                    + " 行），请缩小查询条件后分批导出");
        }
        exported += rows.size();
        lastRow = rows.get(rows.size() - 1);
        return new ArrayList<Object>(rows);
    }

    /** 已产出的数据行数（导出完成后回写到任务表）。 */
    public long getExportedCount() {
        return exported;
    }
}
