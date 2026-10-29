package org.jeecg.modules.wms.exporter;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.jeecgframework.poi.excel.ExcelExportUtil;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.handler.inter.IExcelExportServer;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * @Description: 基于 AutoPoi 的大导出实现。
 *
 * <p>用 {@code exportBigExcel} 而不是 {@code JeecgController.exportXls}：后者把整个 List 塞进内存，
 * 几十万行必 OOM；前者按批回调取数，配合 SXSSF 只保留窗口内的行。
 *
 * <p>{@code SXSSFWorkbook.dispose()} 必须在 finally 里调，否则 POI 的临时文件会一直堆在系统临时目录。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
public class AutoPoiExcelWriter implements WmsExcelWriter {

    @Override
    public void write(ExportParams params, Class<?> clazz, IExcelExportServer server, Object ctx, File target)
            throws IOException {
        Workbook workbook = ExcelExportUtil.exportBigExcel(params, clazz, server, ctx);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target.toPath()))) {
            workbook.write(out);
        } finally {
            try {
                workbook.close();
            } catch (IOException e) {
                log.warn("关闭工作簿失败：{}", e.getMessage());
            }
            if (workbook instanceof SXSSFWorkbook sxssf) {
                sxssf.dispose();
            }
        }
        log.info("导出文件已写出：{}（{} 字节）", target.getAbsolutePath(), target.length());
    }
}
