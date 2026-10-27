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
 * @Description: 入库补偿任务的启动自注册器（与订单补偿任务同构）。
 *
 * <p>{@code sys_quartz_job} 只是平台的注册表元数据，不会自动加载；必须在此幂等自注册，
 * 否则全新环境上任务永远不会执行。启停以 {@code wms.sync.job.enabled} 为准。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
public class WmsInboundSyncJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.SYNC_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsInboundSyncJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    void setJobEnabledForTest(boolean jobEnabled) {
        this.jobEnabled = jobEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!jobEnabled) {
            unscheduleIfPresent();
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.INBOUND_SYNC_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("入库补偿任务[{}]已存在于调度器中，跳过注册", WmsConstants.INBOUND_SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsInboundSyncRetryJob.class)
                    .withIdentity(WmsConstants.INBOUND_SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.INBOUND_SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.INBOUND_SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("入库补偿任务[{}]注册成功，cron={}", WmsConstants.INBOUND_SYNC_JOB_ID, WmsConstants.INBOUND_SYNC_JOB_CRON);
        } catch (Exception e) {
            log.warn("入库补偿任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    /** 关闭开关时主动摘除（Quartz JDBC 存储会持久化 trigger，仅 return 停不掉）。 */
    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.INBOUND_SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("入库补偿任务[{}]已按配置 {} 关闭并摘除",
                        WmsConstants.INBOUND_SYNC_JOB_ID, WmsConstants.SYNC_JOB_ENABLED_KEY);
            }
        } catch (Exception e) {
            log.warn("入库补偿任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
