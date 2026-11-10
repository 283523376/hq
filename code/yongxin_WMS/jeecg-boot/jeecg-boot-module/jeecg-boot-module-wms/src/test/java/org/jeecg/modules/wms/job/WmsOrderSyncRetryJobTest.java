package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 补偿任务单测：单条失败不得中断整轮
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsOrderSyncRetryJobTest {

    /**
     * 3 条候选中第 2 条抛异常 → 第 1、3 条仍必须被处理，且第 2 条不被重复处理。
     */
    @Test
    void scanAndResync_shouldNotAbortRoundOnSingleFailure() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        // 僵死阈值由订单服务提供；必须打桩返回真实 Date，否则为 null 而 any(Date.class) 不匹配 null
        when(orderService.syncStaleBefore()).thenReturn(new Date());

        WmsOrder first = new WmsOrder().setOrderNo("PO001");
        first.setId("1");
        WmsOrder bad = new WmsOrder().setOrderNo("PO002");
        bad.setId("2");
        WmsOrder third = new WmsOrder().setOrderNo("PO003");
        third.setId("3");
        when(orderService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Arrays.asList(first, bad, third));
        when(orderService.claimForSync(any(), any())).thenReturn(1);

        when(syncService.syncOrder(any(), anyBoolean()))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0))
                .thenThrow(new RuntimeException("模拟第 2 条炸了"))
                .thenReturn(new WmsOrderSyncAttempt(true, null, 0));

        WmsOrderSyncRetryJob job = new WmsOrderSyncRetryJob(orderService, syncService);
        job.scanAndResyncForTest();

        // 三条都尝试过：第 2 条虽然炸了，第 3 条仍被处理
        verify(syncService, times(3)).syncOrder(any(), anyBoolean());
    }

    /** 认领失败的候选必须跳过，不调用同步。 */
    @Test
    void scanAndResync_shouldSkipWhenClaimFails() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(orderService.syncStaleBefore()).thenReturn(new Date());
        WmsOrder only = new WmsOrder().setOrderNo("PO001");
        only.setId("1");
        when(orderService.selectSyncCandidates(any(Date.class), anyInt()))
                .thenReturn(Collections.singletonList(only));
        when(orderService.claimForSync(any(), any())).thenReturn(0);

        new WmsOrderSyncRetryJob(orderService, syncService).scanAndResyncForTest();

        verify(syncService, never()).syncOrder(any(), anyBoolean());
    }

    /** 没有候选时必须安静返回，不报错。 */
    @Test
    void scanAndResync_shouldHandleEmptyCandidates() {
        IWmsOrderService orderService = mock(IWmsOrderService.class);
        IWmsOrderSyncService syncService = mock(IWmsOrderSyncService.class);
        when(orderService.syncStaleBefore()).thenReturn(new Date());
        when(orderService.selectSyncCandidates(any(Date.class), anyInt())).thenReturn(Collections.emptyList());

        new WmsOrderSyncRetryJob(orderService, syncService).scanAndResyncForTest();

        verify(syncService, never()).syncOrder(any(), anyBoolean());
    }

    /**
     * Quartz 的 JDBC 存储与「定时任务管理 → 立即执行」都需要一个可访问的**无参构造器**来实例化 Job。
     * 若只提供带参构造器，运行时会在 scheduleJob 处抛
     * 「NoSuchMethodException: ...WmsOrderSyncRetryJob.&lt;init&gt;()」，表现为「立即执行失败」——
     * 而依赖注册器的正常调度路径不受影响，故容易漏测。此用例把该约束钉住。
     */
    @Test
    void shouldExposeNoArgConstructorForQuartz() {
        assertDoesNotThrow(() -> WmsOrderSyncRetryJob.class.getDeclaredConstructor(),
                "Quartz 需要一个可访问的无参构造器，否则「立即执行」会失败");
    }
}
