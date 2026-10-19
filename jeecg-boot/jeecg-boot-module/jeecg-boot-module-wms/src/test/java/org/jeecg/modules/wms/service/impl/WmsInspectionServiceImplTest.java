package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInspectionTask;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
