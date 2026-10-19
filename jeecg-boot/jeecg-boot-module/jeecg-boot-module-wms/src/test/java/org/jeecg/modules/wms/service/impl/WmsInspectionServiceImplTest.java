package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 检验任务（RK-001 step3-4）单测——不启 Spring、不连库。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsInspectionServiceImplTest {

    private IWmsInboundService inboundService;
    private WmsInspectionServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundService = mock(IWmsInboundService.class);
        service = new WmsInspectionServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mock(org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper.class));
        WmsTestSupport.inject(service, "inboundService", inboundService);
    }

    private WmsInspectionTask pendingTask() {
        WmsInspectionTask task = new WmsInspectionTask();
        task.setId("t1");
        task.setTaskNo("JY202610190001");
        task.setInboundId("ib1");
        task.setInspectStatus(WmsConstants.INSPECT_STATUS_PENDING);
        return task;
    }

    @Test
    void completePassedMovesOrderToPendingAllocate() {
        WmsInspectionServiceImpl spyService = org.mockito.Mockito.spy(service);
        doReturn(pendingTask()).when(spyService).getById("t1");

        spyService.complete("t1", WmsConstants.INSPECT_STATUS_PASSED, null);

        verify(inboundService).onInspectResult("ib1", true);
    }

    @Test
    void completeFailedMarksOrderInspectFailed() {
        WmsInspectionServiceImpl spyService = org.mockito.Mockito.spy(service);
        doReturn(pendingTask()).when(spyService).getById("t1");

        spyService.complete("t1", WmsConstants.INSPECT_STATUS_FAILED, "尺寸超差");

        verify(inboundService).onInspectResult("ib1", false);
    }

    @Test
    void completeFailedRequiresRemark() {
        WmsInspectionServiceImpl spyService = org.mockito.Mockito.spy(service);
        doReturn(pendingTask()).when(spyService).getById("t1");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> spyService.complete("t1", WmsConstants.INSPECT_STATUS_FAILED, "  "));
        assertEquals("不合格必须填写原因", ex.getMessage());
    }

    @Test
    void completeRejectsProcessedTask() {
        WmsInspectionServiceImpl spyService = org.mockito.Mockito.spy(service);
        WmsInspectionTask done = pendingTask();
        done.setInspectStatus(WmsConstants.INSPECT_STATUS_PASSED);
        doReturn(done).when(spyService).getById("t1");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> spyService.complete("t1", WmsConstants.INSPECT_STATUS_PASSED, null));
        assertEquals("该任务已处理", ex.getMessage());
    }

    @Test
    void listPageFiltersTerminalOrdersViaJoinQuery() {
        org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper mapper =
                mock(org.jeecg.modules.wms.mapper.WmsInspectionTaskMapper.class);
        WmsTestSupport.inject(service, "baseMapper", mapper);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WmsInspectionTask> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(java.util.Collections.singletonList(pendingTask()));
        page.setTotal(1);
        when(mapper.selectPageWithOrder(any(), any())).thenReturn(page);
        org.jeecg.modules.wms.entity.WmsInboundOrder order =
                new org.jeecg.modules.wms.entity.WmsInboundOrder();
        order.setId("ib1");
        order.setInboundStatus(WmsConstants.INBOUND_STATUS_PENDING_INSPECT);
        order.setOrderNo("SL001");
        when(inboundService.getById("ib1")).thenReturn(order);

        com.baomidou.mybatisplus.core.metadata.IPage<org.jeecg.modules.wms.vo.WmsInspectionTaskVO> result =
                service.listPage(1, 10, WmsConstants.INSPECT_STATUS_PENDING);

        verify(mapper).selectPageWithOrder(any(), org.mockito.ArgumentMatchers.eq(WmsConstants.INSPECT_STATUS_PENDING));
        assertEquals(order.getInboundStatus(), result.getRecords().get(0).getInboundStatus());
    }
}
