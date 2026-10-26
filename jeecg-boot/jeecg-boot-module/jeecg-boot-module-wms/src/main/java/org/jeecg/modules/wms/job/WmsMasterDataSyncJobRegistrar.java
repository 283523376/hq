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
 * @Description: 主数据同步任务的自注册器（默认不注册）。
 *
 * <p>与 {@link WmsOrderSyncJobRegistrar} 同构：Quartz 是 JDBC 作业存储，
 * trigger 一旦注册就持久化在 QRTZ_* 表里，所以关闭时必须**主动摘除**，
 * 否则「配成 false + 重启」看起来停了、实际照跑。
 *
 * <p>开关取 {@code wms.sync.erp.kingdee.master-data.enabled}（默认 false，spec D6）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Component
public class WmsMasterDataSyncJobRegistrar {

    private final Scheduler scheduler;
    private final WmsKingdeeProperties properties;

    public WmsMasterDataSyncJobRegistrar(Scheduler scheduler, WmsKingdeeProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.getMasterData().isEnabled()) {
            unscheduleIfPresent();
            return;
        }
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.MASTER_DATA_SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                log.info("主数据同步任务[{}]已存在，跳过注册", WmsConstants.MASTER_DATA_SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsMasterDataSyncJob.class)
                    .withIdentity(WmsConstants.MASTER_DATA_SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.MASTER_DATA_SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.MASTER_DATA_SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("主数据同步任务[{}]注册成功，cron={}",
                    WmsConstants.MASTER_DATA_SYNC_JOB_ID, WmsConstants.MASTER_DATA_SYNC_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("主数据同步任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.MASTER_DATA_SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("主数据同步任务[{}]已按配置关闭并摘除", WmsConstants.MASTER_DATA_SYNC_JOB_ID);
            }
        } catch (Exception e) {
            log.warn("主数据同步任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
