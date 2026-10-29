package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.enmus.ExcelType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @Description: 库存台账生成（KC-004）。
 *
 * <p>**整个生成过程包在一个只读事务里**：MySQL 的 REPEATABLE READ 会给这个事务一个一致性快照，
 * 于是几十万行分批取数时，即使期间有入库/出库，文件里的数据仍然是同一个时点的，
 * 不会出现「前面几批是旧数、后面几批是新数」的自相矛盾。
 *
 * <p>注意它必须是**独立 Bean**：{@code @Transactional} 只在跨 Bean 调用时生效，同一个类里
 * {@code this.xxx()} 自调用会绕过代理，事务就没了。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Service
public class WmsLedgerExportService {

    private static final String SHEET_NAME = "库存台账";
    private static final String TITLE_PREFIX = "库存台账（快照 ";
    private static final String SECOND_TITLE = "永鑫WMS";
    private static final String SNAPSHOT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final WmsInventoryMapper inventoryMapper;
    private final WmsExcelWriter excelWriter;

    public WmsLedgerExportService(WmsInventoryMapper inventoryMapper, WmsExcelWriter excelWriter) {
        this.inventoryMapper = inventoryMapper;
        this.excelWriter = excelWriter;
    }

    /**
     * 生成一份台账文件。
     *
     * @param query  已归一化的查询条件（见 {@code WmsInventoryLedgerServiceImpl.normalize}）
     * @param target 目标文件；调用方负责父目录与临时后缀
     */
    @Transactional(readOnly = true)
    public WmsLedgerExportResult generate(WmsInventoryLedgerQueryDTO query, File target) throws IOException {
        Date snapshotTime = new Date();
        WmsLedgerExportPageSource source = new WmsLedgerExportPageSource(inventoryMapper, query,
                WmsConstants.INVENTORY_LEDGER_EXPORT_BATCH_SIZE,
                WmsConstants.INVENTORY_LEDGER_EXCEL_MAX_ROWS);

        String title = TITLE_PREFIX
                + new SimpleDateFormat(SNAPSHOT_TIME_PATTERN).format(snapshotTime) + "）";
        ExportParams params = new ExportParams(title, SECOND_TITLE, SHEET_NAME, ExcelType.XSSF);

        excelWriter.write(params, WmsInventoryLedgerVO.class, source, null, target);
        return new WmsLedgerExportResult(source.getExportedCount(), snapshotTime);
    }
}
