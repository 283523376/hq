package org.jeecg.modules.wms.exporter;

import java.util.Date;

/**
 * @Description: 一次台账生成的产出（行数 + 一致性快照时点）。
 *
 * @param rowCount     实际写进文件的数据行数
 * @param snapshotTime 只读事务开始时刻，写进任务表和 Excel 表头
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
public record WmsLedgerExportResult(long rowCount, Date snapshotTime) {
}
