package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
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
 * @Description: 工序汇报同步任务自注册器。
 *
 * <p>与其它 Registrar 同构：Quartz 是 JDBC 作业存储，trigger 一旦注册就持久化在 QRTZ_* 表，
 * 关闭时必须主动摘除，否则「配成 false + 重启」看起来停了、实际照跑。
 *
 * <p>开关取 {@code wms.sync.erp.kingdee.master-data.operation-report.enabled}（默认 true）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
public class WmsOperationReportSyncJobRegistrar {

    private final Scheduler scheduler;
    private final WmsKingdeeProperties properties;

    public WmsOperationReportSyncJobRegistrar(Scheduler scheduler, WmsKingdeeProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.getMasterData().getOperationReport().isEnabled()) {
            unscheduleIfPresent();
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.OPERATION_REPORT_SYNC_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("工序汇报同步任务[{}]已存在，跳过注册", WmsConstants.OPERATION_REPORT_SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsOperationReportSyncJob.class)
                    .withIdentity(WmsConstants.OPERATION_REPORT_SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.OPERATION_REPORT_SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.OPERATION_REPORT_SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("工序汇报同步任务[{}]注册成功，cron={}",
                    WmsConstants.OPERATION_REPORT_SYNC_JOB_ID, WmsConstants.OPERATION_REPORT_SYNC_JOB_CRON);
        } catch (Exception e) {
            log.warn("工序汇报同步任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.OPERATION_REPORT_SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("工序汇报同步任务[{}]已按配置关闭并摘除", WmsConstants.OPERATION_REPORT_SYNC_JOB_ID);
            }
        } catch (Exception e) {
            log.warn("工序汇报同步任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
