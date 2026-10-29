package org.jeecg.modules.wms.exporter;

import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.handler.inter.IExcelExportServer;

import java.io.File;
import java.io.IOException;

/**
 * @Description: 把分批数据源写成 xlsx。
 *
 * <p>抽成接口是为了让执行器能被单测：真正的 AutoPoi 写盘逻辑不好在纯单测里断言，
 * 而状态流转、文件收尾、失败文案这些才是容易出错的地方。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
public interface WmsExcelWriter {

    /**
     * 分批取数并写入目标文件。
     *
     * @param params 表头/工作表配置
     * @param clazz  行对象类型（带 {@code @Excel} 注解）
     * @param server 分批数据源，返回空即结束
     * @param ctx    透传给数据源的上下文
     * @param target 目标文件（调用方保证父目录存在）
     */
    void write(ExportParams params, Class<?> clazz, IExcelExportServer server, Object ctx, File target)
            throws IOException;
}
