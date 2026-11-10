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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * @Description: 调拨 ERP 同步任务的启动自注册器（与其它补偿任务同构）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Component
public class WmsTransferSyncJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.SYNC_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsTransferSyncJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!jobEnabled) {
            unscheduleIfPresent();
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.TRANSFER_SYNC_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("调拨同步任务[{}]已存在于调度器中，跳过注册", WmsConstants.TRANSFER_SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsTransferSyncJob.class)
                    .withIdentity(WmsConstants.TRANSFER_SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.TRANSFER_SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.TRANSFER_SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("调拨同步任务[{}]注册成功，cron={}",
                    WmsConstants.TRANSFER_SYNC_JOB_ID, WmsConstants.TRANSFER_SYNC_JOB_CRON);
        } catch (Exception e) {
            log.warn("调拨同步任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.TRANSFER_SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("调拨同步任务[{}]已按配置 {} 关闭并摘除",
                        WmsConstants.TRANSFER_SYNC_JOB_ID, WmsConstants.SYNC_JOB_ENABLED_KEY);
            }
        } catch (Exception e) {
            log.warn("调拨同步任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
