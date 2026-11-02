package org.jeecg.modules.wms.exporter;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @Description: 审计日志导出（XT-002），复用 KC-004 的异步任务与 AutoPoi 流式写盘。
 *
 * <p>无数据时**不生成空文件**：只有表头的文件会被误当成「这段时间没有任何操作」，
 * 而真实原因往往是筛选条件写错了。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Service
public class WmsAuditExportService {

    private static final String SHEET_NAME = "审计日志";
    private static final String TITLE_PREFIX = "审计日志（导出时刻 ";
    private static final String SECOND_TITLE = "永鑫WMS";
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final WmsAuditLogMapper auditLogMapper;
    private final WmsExcelWriter excelWriter;

    public WmsAuditExportService(WmsAuditLogMapper auditLogMapper, WmsExcelWriter excelWriter) {
        this.auditLogMapper = auditLogMapper;
        this.excelWriter = excelWriter;
    }

    /**
     * 生成一份审计日志文件。
     *
     * @param query  查询条件（已由调用方转成对象）
     * @param target 目标文件；调用方负责父目录与临时后缀
     * @throws RuntimeException 没有符合条件的日志
     *
     * <p>**刻意不加只读事务**（评审 M7）：审计表只增不改，(create_time,id) 游标分页在无事务下
     * 依然稳定；长事务包住整表扫描会让 undo 无法回收，还和清理 Job 的批量 DELETE 互相拖慢。
     */
    public WmsLedgerExportResult generate(WmsAuditQueryDTO query, File target) throws IOException {
        Date snapshotTime = new Date();
        Long total = auditLogMapper.selectCount(WmsAuditExportPageSource.filter(query));
        if (total == null || total == 0) {
            throw new RuntimeException("没有符合条件的日志，未生成文件");
        }

        WmsAuditExportPageSource source = new WmsAuditExportPageSource(auditLogMapper, query,
                WmsConstants.AUDIT_EXPORT_BATCH_SIZE, WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS);
        ExportParams params = new ExportParams(
                TITLE_PREFIX + new SimpleDateFormat(TIME_PATTERN).format(snapshotTime) + "）",
                SECOND_TITLE, SHEET_NAME, ExcelType.XSSF);
        params.setMaxNum(WmsConstants.INVENTORY_LEDGER_SHEET_PHYSICAL_MAX);

        excelWriter.write(params, WmsAuditLog.class, source, null, target);
        return new WmsLedgerExportResult(source.getExportedCount(), snapshotTime);
    }
}
