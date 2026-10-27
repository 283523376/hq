package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 补偿任务启动注册器单测
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderSyncJobRegistrarTest {

    private WmsOrderSyncJobRegistrar registrar(Scheduler scheduler, boolean enabled) {
        WmsOrderSyncJobRegistrar registrar = new WmsOrderSyncJobRegistrar(scheduler);
        registrar.setJobEnabledForTest(enabled);
        return registrar;
    }

    /** 开启且调度器中不存在 → 必须注册。 */
    @Test
    void register_whenEnabledAndAbsent_shouldSchedule() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        registrar(scheduler, true).register();

        verify(scheduler, times(1)).scheduleJob(any(), any());
    }

    /** 开启且已存在（JobDetail + trigger 都在）→ 幂等跳过，不重复注册。 */
    @Test
    void register_whenEnabledAndPresent_shouldSkip() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);
        doReturn(List.of(mock(Trigger.class))).when(scheduler).getTriggersOfJob(any(JobKey.class));

        registrar(scheduler, true).register();

        verify(scheduler, never()).scheduleJob(any(), any());
    }

    /**
     * 只剩 JobDetail、trigger 已被 UI 删掉 → 必须删掉残留并重建。
     *
     * <p>{@code checkExists} 只判 JobDetail；若据此直接跳过注册，任务将**永不触发**，
     * 而日志还写着「已存在，跳过注册」——静默失效。
     */
    @Test
    void register_whenJobDetailLeftWithoutTrigger_shouldRebuild() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);
        doReturn(List.of()).when(scheduler).getTriggersOfJob(any(JobKey.class));

        registrar(scheduler, true).register();

        verify(scheduler, times(1)).deleteJob(any(JobKey.class));
        verify(scheduler, times(1)).scheduleJob(any(), any());
    }

    /**
     * 关闭时**必须主动摘除**已存在的任务。
     *
     * <p>本部署的 Quartz 是 JDBC 作业存储（{@code LocalDataSourceJobStore} + {@code isClustered:true}），
     * 一旦注册过，trigger 就持久化在 QRTZ_* 表里，重启后会被调度器自动加载。
     * 若注册器在关闭时只是"提前 return"，任务仍会每 5 分钟照跑——运维按配置写成 false 后会误判「已停」。
     */
    @Test
    void register_whenDisabledAndPresent_shouldUnschedule() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(true);

        registrar(scheduler, false).register();

        verify(scheduler, times(1)).deleteJob(any(JobKey.class));
        verify(scheduler, never()).scheduleJob(any(), any());
    }

    /** 关闭且调度器中无残留 → 安静返回，不报错，也不注册。 */
    @Test
    void register_whenDisabledAndAbsent_shouldDoNothing() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);

        registrar(scheduler, false).register();

        verify(scheduler, never()).scheduleJob(any(), any());
        verify(scheduler, never()).deleteJob(any(JobKey.class));
    }

    /**
     * 调度器出问题不得把应用启动带崩。
     *
     * <p>查不到调度状态时按「未注册」处理并尝试重建：此时 scheduleJob 多半也会失败，
     * 再次被注册器兜住——比「查不到就放弃、任务从此不再注册」更安全。
     */
    @Test
    void register_shouldNotPropagateSchedulerFailure() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenThrow(new RuntimeException("scheduler 挂了"));

        assertDoesNotThrow(() -> registrar(scheduler, true).register());
    }

    /**
     * job key 必须与 {@code V3.9.5_4__wms_supplier_order_menu_job.sql} 里 sys_quartz_job 种子行的 id 同值。
     * 二者不一致时，「定时任务管理」UI 的启停/立即执行会作用在另一个 JobKey 上，而注册的任务照跑。
     */
    @Test
    void jobIdShouldMatchFlywaySeedRow() {
        assertEquals("1920000000000000101", WmsConstants.SYNC_JOB_ID,
                "SYNC_JOB_ID 必须与 Flyway 种子行的 id 一致，否则 UI 控制的是另一个任务");
    }
}
