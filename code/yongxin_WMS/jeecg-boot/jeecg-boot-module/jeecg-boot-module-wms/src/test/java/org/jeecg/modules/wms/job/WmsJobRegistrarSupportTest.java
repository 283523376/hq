package org.jeecg.modules.wms.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 定时任务注册守卫单测——四种调度状态口径（2026-11-12 正式 QA P4）
 *
 * <p>背景：ERROR 态 trigger 是「永不触发」的静默失效（11-06 两个任务滞留两天），
 * {@code isScheduled} 必须把它与「无 trigger」同样视为残留并删除重建。
 */
class WmsJobRegistrarSupportTest {

    private Scheduler scheduler;
    private final JobKey jobKey = JobKey.jobKey("1920000000000000102");

    @BeforeEach
    void setUp() {
        scheduler = mock(Scheduler.class);
    }

    /** JobDetail 不存在 → 未注册。 */
    @Test
    void isScheduled_shouldReturnFalseWhenJobDetailMissing() throws Exception {
        when(scheduler.checkExists(jobKey)).thenReturn(false);

        assertFalse(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }

    /** 正常 WAITING trigger → 已注册，不动调度。 */
    @Test
    void isScheduled_shouldReturnTrueWhenTriggerWaiting() throws Exception {
        Trigger trigger = triggerWithKey("t1");
        when(scheduler.checkExists(jobKey)).thenReturn(true);
        doReturn(List.of(trigger)).when(scheduler).getTriggersOfJob(jobKey);
        when(scheduler.getTriggerState(trigger.getKey())).thenReturn(Trigger.TriggerState.NORMAL);

        assertTrue(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }

    /** 只有 JobDetail 无 trigger（UI 删了 trigger）→ 残留，删除重建。 */
    @Test
    void isScheduled_shouldDeleteResidueWhenNoTrigger() throws Exception {
        when(scheduler.checkExists(jobKey)).thenReturn(true);
        doReturn(List.of()).when(scheduler).getTriggersOfJob(jobKey);

        assertFalse(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
        verify(scheduler).deleteJob(jobKey);
    }

    /**
     * trigger 存在但 ERROR 态（起火时刻 JVM 被杀等导致 JDBC store 标 ERROR，永不再触发）
     * → 残留，删除重建。这是 2026-11-06 两个生产任务滞留两天的根因。
     */
    @Test
    void isScheduled_shouldDeleteResidueWhenTriggerInErrorState() throws Exception {
        Trigger trigger = triggerWithKey("t-error");
        when(scheduler.checkExists(jobKey)).thenReturn(true);
        doReturn(List.of(trigger)).when(scheduler).getTriggersOfJob(jobKey);
        when(scheduler.getTriggerState(trigger.getKey())).thenReturn(Trigger.TriggerState.ERROR);

        assertFalse(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
        verify(scheduler).deleteJob(jobKey);
    }

    /** 多 trigger 中只要有一个 ERROR 即视为残留（部分损坏与全损坏同样不可用）。 */
    @Test
    void isScheduled_shouldDeleteResidueWhenAnyTriggerInErrorState() throws Exception {
        Trigger healthy = triggerWithKey("t-ok");
        Trigger broken = triggerWithKey("t-broken");
        when(scheduler.checkExists(jobKey)).thenReturn(true);
        doReturn(List.of(healthy, broken)).when(scheduler).getTriggersOfJob(jobKey);
        when(scheduler.getTriggerState(healthy.getKey())).thenReturn(Trigger.TriggerState.NORMAL);
        when(scheduler.getTriggerState(broken.getKey())).thenReturn(Trigger.TriggerState.ERROR);

        assertFalse(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
        verify(scheduler).deleteJob(jobKey);
    }

    /** 查询调度状态本身抛异常 → 保守按未注册处理（调用方会重建调度）。 */
    @Test
    void isScheduled_shouldTreatCheckFailureAsUnscheduled() throws Exception {
        when(scheduler.checkExists(jobKey)).thenReturn(true);
        when(scheduler.getTriggersOfJob(jobKey)).thenThrow(new RuntimeException("调度器抖动"));

        assertFalse(WmsJobRegistrarSupport.isScheduled(scheduler, jobKey));
    }

    private static Trigger triggerWithKey(String name) {
        Trigger trigger = mock(Trigger.class);
        when(trigger.getKey()).thenReturn(TriggerKey.triggerKey(name));
        return trigger;
    }
}
