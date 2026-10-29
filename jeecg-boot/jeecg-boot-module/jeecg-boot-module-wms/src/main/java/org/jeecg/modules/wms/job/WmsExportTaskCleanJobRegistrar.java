package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.WmsExportProperties;
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
 * @Description: 导出任务清理任务自注册器（KC-004）。
 *
 * <p>与其它 Registrar 同构：Quartz 是 JDBC 作业存储，trigger 一旦注册就持久化在 QRTZ_* 表，
 * 关闭时必须**主动摘除**，否则「配成 false + 重启」看起来停了、实际照跑。
 *
 * <p>开关取 {@code wms.export.clean.enabled}（默认 true）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
public class WmsExportTaskCleanJobRegistrar {

    private final Scheduler scheduler;
    private final WmsExportProperties properties;

    public WmsExportTaskCleanJobRegistrar(Scheduler scheduler, WmsExportProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.getClean().isEnabled()) {
            unscheduleIfPresent();
            return;
        }
        String cron = properties.getClean().getCron() == null
                ? WmsConstants.EXPORT_TASK_CLEAN_JOB_CRON : properties.getClean().getCron();
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.EXPORT_TASK_CLEAN_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("导出任务清理[{}]已存在，跳过注册", WmsConstants.EXPORT_TASK_CLEAN_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsExportTaskCleanJob.class)
                    .withIdentity(WmsConstants.EXPORT_TASK_CLEAN_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.EXPORT_TASK_CLEAN_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("导出任务清理[{}]注册成功，cron={}", WmsConstants.EXPORT_TASK_CLEAN_JOB_ID, cron);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("导出任务清理注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.EXPORT_TASK_CLEAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("导出任务清理[{}]已按配置关闭并摘除", WmsConstants.EXPORT_TASK_CLEAN_JOB_ID);
            }
        } catch (Exception e) {
            log.warn("导出任务清理摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
