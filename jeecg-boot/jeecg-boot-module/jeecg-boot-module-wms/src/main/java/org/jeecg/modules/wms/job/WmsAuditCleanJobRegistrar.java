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
 * @Description: 审计日志清理任务自注册器（XT-002）。
 *
 * <p>与其它 Registrar 同构：Quartz 是 JDBC 作业存储，trigger 持久化在 QRTZ_* 表。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Component
public class WmsAuditCleanJobRegistrar {

    private final Scheduler scheduler;

    public WmsAuditCleanJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.AUDIT_CLEAN_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("审计日志清理[{}]已存在，跳过注册", WmsConstants.AUDIT_CLEAN_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsAuditCleanJob.class)
                    .withIdentity(WmsConstants.AUDIT_CLEAN_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.AUDIT_CLEAN_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.AUDIT_CLEAN_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("审计日志清理[{}]注册成功，cron={}", WmsConstants.AUDIT_CLEAN_JOB_ID,
                    WmsConstants.AUDIT_CLEAN_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("审计日志清理注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
