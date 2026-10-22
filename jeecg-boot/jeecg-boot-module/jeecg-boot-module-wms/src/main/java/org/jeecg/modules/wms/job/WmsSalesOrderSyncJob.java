package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.IWmsSalesOrderService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

/**
 * @Description: 销售订单拉取任务（CK-002 方向反转：ERP → WMS）。
 *
 * <p>每轮回看近 {@code SALES_ORDER_SYNC_DAYS} 天、状态为「已审核(C)」的销售订单，幂等 upsert 快照。
 * 单条失败不中断整轮；连续失败 3 次由服务层告警。金蝶未启用时直接跳过。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsSalesOrderSyncJob implements Job {

    @Autowired
    private IWmsSalesOrderService salesOrderService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsSalesOrderSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsSalesOrderSyncJob(IWmsSalesOrderService salesOrderService) {
        this.salesOrderService = salesOrderService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        runOnce();
    }

    void runOnce() {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, -WmsConstants.SALES_ORDER_SYNC_DAYS);
            Date begin = calendar.getTime();
            Date end = new Date();
            int saved = salesOrderService.syncByWindow(begin, end);
            if (saved > 0) {
                log.info("销售订单拉取完成：{} 单（窗口 {} 天）", saved, WmsConstants.SALES_ORDER_SYNC_DAYS);
            }
        } catch (Exception e) {
            log.warn("销售订单拉取任务异常（不影响下一轮）：{}", e.getMessage(), e);
        }
    }
}
