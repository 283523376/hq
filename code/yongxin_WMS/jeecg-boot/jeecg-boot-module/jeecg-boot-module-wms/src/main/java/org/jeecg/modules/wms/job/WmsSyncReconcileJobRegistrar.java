package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * @Description: SB-001 定时对账任务自注册器。
 *
 * <p>与其它 Registrar 逐字同构：Quartz 是 JDBC 作业存储，trigger 持久化在 QRTZ_* 表。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Component
public class WmsSyncReconcileJobRegistrar {

    private final Scheduler scheduler;

    public WmsSyncReconcileJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.SYNC_RECONCILE_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("定时对账[{}]已存在，跳过注册", WmsConstants.SYNC_RECONCILE_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsSyncReconcileJob.class)
                    .withIdentity(WmsConstants.SYNC_RECONCILE_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.SYNC_RECONCILE_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.SYNC_RECONCILE_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("定时对账[{}]注册成功，cron={}", WmsConstants.SYNC_RECONCILE_JOB_ID,
                    WmsConstants.SYNC_RECONCILE_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("定时对账注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
