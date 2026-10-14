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
 * @Description: 补偿任务的启动自注册器。
 *
 * <p>为什么要它：`sys_quartz_job` 表只是平台的注册表元数据，项目里**没有**任何启动加载器，
 * 任务只经「定时任务管理」UI 的 saveAndScheduleJob 写入调度器。若只靠 Flyway 插一行，
 * 全新环境上任务永远不会执行——自愈会静默失效。故在此幂等自注册。
 *
 * <p>启停以配置 {@code wms.sync.job.enabled} 为准（默认 true）。刻意**不读**
 * sys_quartz_job.status，以免 wms 模块反向依赖 system-biz 的 QuartzJob 实体。
 *
 * <p>约定与 {@code QuartzJobServiceImpl.schedulerAdd} 对齐：JobDetail/CronTrigger 均用
 * withIdentity(id) 的**默认组**，这样「定时任务管理」UI 的启停按钮控制的是同一个 job。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
public class WmsOrderSyncJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.SYNC_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsOrderSyncJobRegistrar(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** 仅供单测设置开关。 */
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
            JobKey jobKey = JobKey.jobKey(WmsConstants.SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                log.info("补偿任务[{}]已存在于调度器中，跳过注册", WmsConstants.SYNC_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsOrderSyncRetryJob.class)
                    .withIdentity(WmsConstants.SYNC_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.SYNC_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.SYNC_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("补偿任务[{}]注册成功，cron={}", WmsConstants.SYNC_JOB_ID, WmsConstants.SYNC_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("补偿任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 关闭开关时**主动摘除**已注册的任务。
     *
     * <p>本部署的 Quartz 是 JDBC 作业存储（{@code LocalDataSourceJobStore} + {@code isClustered:true}），
     * 任务一旦注册过，其 trigger 就持久化在 QRTZ_* 表中，重启后会被调度器自动加载。
     * 因此仅在关闭时"提前 return"是**停不掉**任务的：运维把 {@code wms.sync.job.enabled} 改成 false 并重启后，
     * 日志会显示"已关闭"，任务却仍每 5 分钟照跑——属"看起来已停"的静默故障。
     */
    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.SYNC_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("补偿任务[{}]已按配置 {} 关闭并摘除", WmsConstants.SYNC_JOB_ID, WmsConstants.SYNC_JOB_ENABLED_KEY);
            } else {
                log.info("补偿任务已按配置 {} 关闭，调度器中无残留", WmsConstants.SYNC_JOB_ENABLED_KEY);
            }
        } catch (Exception e) {
            log.warn("补偿任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
