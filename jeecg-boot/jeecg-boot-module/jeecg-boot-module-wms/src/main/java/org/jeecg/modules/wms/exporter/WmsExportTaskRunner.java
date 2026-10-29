package org.jeecg.modules.wms.exporter;

/**
 * @Description: 导出任务执行入口。
 *
 * <p>定义成接口是为了让「提交任务」和「执行任务」可以分两步落地：
 * 提交侧（{@code WmsExportTaskServiceImpl}）只依赖这个接口把活丢进线程池，
 * 真正读库写 Excel 的实现由 {@code WmsExportTaskRunnerImpl} 提供。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
public interface WmsExportTaskRunner {

    /**
     * 执行一条导出任务（在导出线程池里跑，**没有 Shiro Subject**）。
     *
     * @param taskId   任务主键
     * @param operator 导出人账号，由请求线程传进来，后台线程不要自己取
     */
    void execute(String taskId, String operator);
}
