package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;

/**
 * @Description: 导出任务（KC-004）。
 *
 * <p>所有方法都要求显式传 {@code operator}——导出线程池里没有 Shiro Subject，
 * Service 内部一律不碰 {@code WmsUtils}，登录人由 Controller 在请求线程里解析后传进来。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
public interface IWmsExportTaskService {

    /**
     * 创建一条库存台账导出任务并入队。
     *
     * @return 任务主键
     * @throws RuntimeException 已有进行中任务 / 线程池队列已满
     */
    String createLedgerExport(WmsInventoryLedgerQueryDTO query, String operator);

    /** 当前账号的导出任务分页（强制按 operator 过滤，不接受外部指定账号）。 */
    IPage<WmsExportTask> pageMine(String operator, int pageNo, int pageSize);
}
