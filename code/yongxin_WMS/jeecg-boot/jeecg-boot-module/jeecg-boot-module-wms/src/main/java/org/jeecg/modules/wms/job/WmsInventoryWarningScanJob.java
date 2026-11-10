package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsInventoryWarningService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Description: 库存预警定时扫描任务（KC-001）。
 *
 * <p>与 {@link WmsStocktakePlanJob} 同构：由 Registrar 注册、关闭时主动摘除；
 * 执行体吞异常——扫描失败只记日志，不影响应用与其它任务。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsInventoryWarningScanJob implements Job {

    @Autowired
    private IWmsInventoryWarningService warningService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsInventoryWarningScanJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsInventoryWarningScanJob(IWmsInventoryWarningService warningService) {
        this.warningService = warningService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanForTest();
    }

    void scanForTest() {
        try {
            warningService.scan();
        } catch (Exception e) {
            log.error("库存预警扫描任务失败：{}", e.getMessage(), e);
        }
    }
}
