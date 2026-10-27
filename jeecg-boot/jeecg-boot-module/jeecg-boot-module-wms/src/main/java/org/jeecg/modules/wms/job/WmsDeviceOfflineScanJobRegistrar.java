package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

import java.util.List;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 离线扫描任务的启动自注册器。
 *
 * <p>为什么要它：{@code sys_quartz_job} 表只是平台的注册表元数据，项目里**没有**任何启动加载器，
 * 任务只经「定时任务管理」UI 的 saveAndScheduleJob 写入调度器。若只靠 Flyway 插一行，
 * 全新环境上任务永远不会执行 —— 离线告警会静默失效。故在此幂等自注册。
 *
 * <p>启停以配置 {@code wms.device.job.enabled} 为准（默认 true）。刻意**不读**
 * sys_quartz_job.status，以免 wms 模块反向依赖 system-biz 的 QuartzJob 实体。
 *
 * <p>约定与 {@code QuartzJobServiceImpl.schedulerAdd} 对齐：JobDetail / CronTrigger 均用
 * withIdentity(id) 的**默认组**，这样 UI 的启停按钮控制的是同一个 job。
 *
 * <p>与 {@link WmsOrderSyncJobRegistrar} 是同构的两份代码，**刻意不抽象**：
 * 抽象需改动一个已上线并已验收的类，收益仅约 30 行，风险大于收益。
 * 若出现第三个任务，再抽公共基类。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Component
public class WmsDeviceOfflineScanJobRegistrar {

    private final Scheduler scheduler;

    @Value("${" + WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY + ":true}")
    private boolean jobEnabled;

    public WmsDeviceOfflineScanJobRegistrar(Scheduler scheduler) {
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
            JobKey jobKey = JobKey.jobKey(WmsConstants.DEVICE_SCAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                // 只查 JobDetail 不够：若有人经 UI 只删了 trigger 而 JobDetail 残留，
                // 直接 return 会让任务永不触发（静默失效）。此时删掉残留 JobDetail 重建。
                List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
                if (triggers != null && !triggers.isEmpty()) {
                    log.info("设备离线扫描任务[{}]已存在于调度器中，跳过注册", WmsConstants.DEVICE_SCAN_JOB_ID);
                    return;
                }
                log.warn("设备离线扫描任务[{}]存在残留 JobDetail 但无 trigger，重建调度", WmsConstants.DEVICE_SCAN_JOB_ID);
                scheduler.deleteJob(jobKey);
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsDeviceOfflineScanJob.class)
                    .withIdentity(WmsConstants.DEVICE_SCAN_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.DEVICE_SCAN_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(WmsConstants.DEVICE_SCAN_JOB_CRON))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("设备离线扫描任务[{}]注册成功，cron={}",
                    WmsConstants.DEVICE_SCAN_JOB_ID, WmsConstants.DEVICE_SCAN_JOB_CRON);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("设备离线扫描任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    /**
     * 关闭开关时**主动摘除**已注册的任务。
     *
     * <p>本部署的 Quartz 是 JDBC 作业存储（{@code LocalDataSourceJobStore} + {@code isClustered:true}），
     * 任务一旦注册过，其 trigger 就持久化在 QRTZ_* 表中，重启后会被调度器自动加载。
     * 因此仅在关闭时"提前 return"是**停不掉**任务的：运维把配置改成 false 并重启后，
     * 日志会显示"已关闭"，任务却仍每 5 分钟照跑 —— 属"看起来已停"的静默故障。
     */
    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.DEVICE_SCAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("设备离线扫描任务[{}]已按配置 {} 关闭并摘除",
                        WmsConstants.DEVICE_SCAN_JOB_ID, WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY);
            } else {
                log.info("设备离线扫描任务已按配置 {} 关闭，调度器中无残留",
                        WmsConstants.DEVICE_SCAN_JOB_ENABLED_KEY);
            }
        } catch (Exception e) {
            log.warn("设备离线扫描任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}