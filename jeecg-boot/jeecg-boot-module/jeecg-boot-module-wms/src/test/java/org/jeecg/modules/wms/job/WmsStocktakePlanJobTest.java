package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsStocktakePlanService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @Description: 盘点计划 Job——吞异常不外抛（照既有补偿任务口径）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakePlanJobTest {

    @Test
    void shouldSwallowPlanExceptions() {
        IWmsStocktakePlanService planService = mock(IWmsStocktakePlanService.class);
        doThrow(new RuntimeException("数据库超时")).when(planService).runDuePlans();
        WmsStocktakePlanJob job = new WmsStocktakePlanJob(planService);

        assertDoesNotThrow(job::syncForTest);
        verify(planService).runDuePlans();
    }
}
