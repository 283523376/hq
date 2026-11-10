package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsStocktakePlanService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Description: 盘点计划执行任务（定期/周期滚动自动建单）。
 *
 * <p>注册节奏照 {@code WmsMasterDataSyncJobRegistrar}：由 Registrar 在启动时注册，
 * 关闭时主动摘除。执行体吞异常——单计划失败不影响应用与其他计划。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsStocktakePlanJob implements Job {

    @Autowired
    private IWmsStocktakePlanService planService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsStocktakePlanJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsStocktakePlanJob(IWmsStocktakePlanService planService) {
        this.planService = planService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    void syncForTest() {
        try {
            planService.runDuePlans();
        } catch (Exception e) {
            log.error("盘点计划任务失败：{}", e.getMessage(), e);
        }
    }
}
