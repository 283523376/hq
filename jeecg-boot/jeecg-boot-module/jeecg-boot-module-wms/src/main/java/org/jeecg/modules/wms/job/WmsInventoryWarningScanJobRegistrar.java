package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.WmsWarningProperties;
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
 * @Description: 库存预警扫描任务自注册器。
 *
 * <p>与其它 Registrar 同构：Quartz 是 JDBC 作业存储，trigger 一旦注册就持久化在 QRTZ_* 表，
 * 关闭时必须**主动摘除**，否则「配成 false + 重启」看起来停了、实际照跑。
 *
 * <p>开关取 {@code wms.warning.scan.enabled}（默认 **true**，spec D10）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Slf4j
@Component
public class WmsInventoryWarningScanJobRegistrar {

    private final Scheduler scheduler;
    private final WmsWarningProperties properties;

    public WmsInventoryWarningScanJobRegistrar(Scheduler scheduler, WmsWarningProperties properties) {
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (!properties.getScan().isEnabled()) {
            unscheduleIfPresent();
            return;
        }
        String cron = properties.getScan().getCron() == null
                ? WmsConstants.INVENTORY_WARNING_SCAN_JOB_CRON : properties.getScan().getCron();
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID);
            if (WmsJobRegistrarSupport.isScheduled(scheduler, jobKey)) {
                log.info("库存预警扫描任务[{}]已存在，跳过注册", WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID);
                return;
            }
            JobDetail jobDetail = JobBuilder.newJob(WmsInventoryWarningScanJob.class)
                    .withIdentity(WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID)
                    .build();
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("库存预警扫描任务[{}]注册成功，cron={}",
                    WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID, cron);
        } catch (Exception e) {
            // 调度器的毛病不允许阻断应用启动
            log.warn("库存预警扫描任务注册失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }

    private void unscheduleIfPresent() {
        try {
            JobKey jobKey = JobKey.jobKey(WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("库存预警扫描任务[{}]已按配置关闭并摘除", WmsConstants.INVENTORY_WARNING_SCAN_JOB_ID);
            }
        } catch (Exception e) {
            log.warn("库存预警扫描任务摘除失败（不影响应用启动）：{}", e.getMessage(), e);
        }
    }
}
