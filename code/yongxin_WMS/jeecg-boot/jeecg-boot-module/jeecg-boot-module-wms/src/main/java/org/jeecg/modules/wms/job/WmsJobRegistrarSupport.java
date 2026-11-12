package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

import java.util.List;

/**
 * @Description: 定时任务自注册的公共守卫。
 *
 * <p>为什么需要它：Quartz 的 {@code checkExists(jobKey)} 只判 **JobDetail** 是否存在。
 * 若有人经「定时任务管理」UI 只删了 trigger 而 JobDetail 残留，注册器会以为任务已在、
 * 直接 return，于是任务**永远不会再触发**——日志里还写着「已存在，跳过注册」，属静默失效。
 *
 * <p>统一口径：JobDetail 无 trigger 视为残留，删掉重建。
 *
 * <p>姊妹坑（2026-11-12 正式 QA P4 实测）：trigger **存在但处于 ERROR 态**同样永不触发——
 * Quartz 在触发失败（如该起火时刻 JVM 被杀、调度中断）时把 JDBC store 里的 trigger 标成
 * ERROR，此后既不触发也不自愈，而「存在性检查」照样通过。ERROR 态同样视为残留，删掉重建。
 * 实测案例：1920000000000000102（设备离线扫描）与 1920000000000000390（工序汇报同步）
 * 自 2026-11-06 13:41 起 ERROR 滞留两天，每次重启日志均写「已存在，跳过注册」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
@Slf4j
public final class WmsJobRegistrarSupport {

    private WmsJobRegistrarSupport() {
    }

    /**
     * @return true = 任务已正常存在（有 JobDetail 且有**非 ERROR** trigger），调用方应跳过注册
     */
    public static boolean isScheduled(Scheduler scheduler, JobKey jobKey) {
        try {
            if (!scheduler.checkExists(jobKey)) {
                return false;
            }
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            if (triggers == null || triggers.isEmpty()) {
                log.warn("任务[{}]存在残留 JobDetail 但无 trigger，删除后重建调度", jobKey.getName());
                scheduler.deleteJob(jobKey);
                return false;
            }
            for (Trigger trigger : triggers) {
                Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
                if (state == Trigger.TriggerState.ERROR) {
                    log.warn("任务[{}]的 trigger[{}] 处于 ERROR 态（永不再触发），删除后重建调度",
                            jobKey.getName(), trigger.getKey());
                    scheduler.deleteJob(jobKey);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("检查任务[{}]调度状态失败，按未注册处理：{}", jobKey.getName(), e.getMessage());
            return false;
        }
    }
}
