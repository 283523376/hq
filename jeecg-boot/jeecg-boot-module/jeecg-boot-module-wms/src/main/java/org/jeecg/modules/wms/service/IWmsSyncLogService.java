package org.jeecg.modules.wms.service;

import org.jeecg.common.system.base.service.JeecgService;
import org.jeecg.modules.wms.entity.WmsSyncLog;

import java.util.List;

/**
 * @Description: 上位系统同步日志（BD-002 引入，SB-001 共用底座）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsSyncLogService extends JeecgService<WmsSyncLog> {

    /**
     * 写一条同步日志（报文脱敏 + 超 8KB 截断后落库）。
     *
     * @param direction      方向：ERP_TO_WMS/WMS_TO_ERP/WMS_TO_MES
     * @param objectType     对象类型：WORK_ORDER/SALES_ORDER/...
     * @param objectNo       业务单号
     * @param target         对端：KINGDEE/MES/WCS/RMS
     * @param success        是否成功
     * @param retryCount     本单当前连续失败次数
     * @param requestPayload 请求报文（可空）
     * @param responsePayload 响应报文（可空）
     * @param errorMessage   失败原因（可空）
     * @param costMs         耗时毫秒
     */
    void logSync(String direction, String objectType, String objectNo, String target,
                 boolean success, int retryCount,
                 String requestPayload, String responsePayload,
                 String errorMessage, long costMs);

    /**
     * 近 N 天内同步失败、且连续失败次数未达上限的对象单号（去重），供定时任务重试。
     *
     * @param objectType 对象类型
     * @param maxRetry   连续失败次数上限（不含）
     * @param withinDays 近 N 天
     */
    List<String> listFailedObjectNos(String objectType, int maxRetry, int withinDays);
}
