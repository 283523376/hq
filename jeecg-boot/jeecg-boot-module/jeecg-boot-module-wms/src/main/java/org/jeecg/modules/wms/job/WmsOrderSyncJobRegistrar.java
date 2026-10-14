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

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!jobEnabled) {
            log.info("补偿任务已按配置 {} 关闭，跳过注册", WmsConstants.SYNC_JOB_ENABLED_KEY);
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
}
