package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.service.IWmsSalesOrderService;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 销售订单拉取任务：按窗口拉取、异常不中断。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsSalesOrderSyncJobTest {

    @Test
    void runOnce_callsSyncByWindow() {
        IWmsSalesOrderService service = mock(IWmsSalesOrderService.class);
        when(service.syncByWindow(any(Date.class), any(Date.class))).thenReturn(2);
        new WmsSalesOrderSyncJob(service).runOnce();
        verify(service).syncByWindow(any(Date.class), any(Date.class));
    }

    @Test
    void runOnce_swallowsException() {
        IWmsSalesOrderService service = mock(IWmsSalesOrderService.class);
        doThrow(new RuntimeException("金蝶不可达")).when(service).syncByWindow(any(Date.class), any(Date.class));
        new WmsSalesOrderSyncJob(service).runOnce();
        verify(service).syncByWindow(any(Date.class), any(Date.class));
    }
}
