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
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
@Slf4j
public final class WmsJobRegistrarSupport {

    private WmsJobRegistrarSupport() {
    }

    /**
     * @return true = 任务已正常存在（有 JobDetail 且有 trigger），调用方应跳过注册
     */
    public static boolean isScheduled(Scheduler scheduler, JobKey jobKey) {
        try {
            if (!scheduler.checkExists(jobKey)) {
                return false;
            }
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            if (triggers != null && !triggers.isEmpty()) {
                return true;
            }
            log.warn("任务[{}]存在残留 JobDetail 但无 trigger，删除后重建调度", jobKey.getName());
            scheduler.deleteJob(jobKey);
            return false;
        } catch (Exception e) {
            log.warn("检查任务[{}]调度状态失败，按未注册处理：{}", jobKey.getName(), e.getMessage());
            return false;
        }
    }
}
