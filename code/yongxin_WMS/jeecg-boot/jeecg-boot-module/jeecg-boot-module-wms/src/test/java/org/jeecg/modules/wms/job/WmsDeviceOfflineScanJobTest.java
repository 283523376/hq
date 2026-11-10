package org.jeecg.modules.wms.job;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备离线扫描任务单测
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceOfflineScanJobTest {

    private static WmsDevice device(String code) {
        WmsDevice d = new WmsDevice().setDeviceCode(code)
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        d.setId("D-" + code);
        return d;
    }

    private static WmsDeviceOfflineScanJob newJob(IWmsDeviceService deviceService,
                                                  IWmsDeviceAlertService alertService) {
        WmsDeviceOfflineScanJob job = new WmsDeviceOfflineScanJob();
        WmsTestSupport.inject(job, "wmsDeviceService", deviceService);
        WmsTestSupport.inject(job, "wmsDeviceAlertService", alertService);
        return job;
    }

    @Test
    void execute_shouldAlertAggregatedForTransitions() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices())
                .thenReturn(Arrays.asList(device("DDJ01"), device("SCR01")));
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean()))
                .thenReturn(Arrays.asList("DDJ01", "SCR01"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        job.execute(mock(JobExecutionContext.class));

        verify(alertService).alertOffline(anyList(), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void execute_shouldNotAlertWhenNoTransitions() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenReturn(Collections.emptyList());
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        job.execute(mock(JobExecutionContext.class));

        verify(alertService, never()).alertOffline(anyList(), anyBoolean());
    }

    /**
     * 扫描异常不得抛回调度器 —— 否则 Quartz 会把 job 置为 ERROR 状态，
     * 后续轮次可能不再触发，「离线告警」整条链路静默死掉。
     */
    @Test
    void execute_shouldSwallowScanFailure() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenThrow(new RuntimeException("数据库连接超时"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, mock(IWmsDeviceAlertService.class));

        assertDoesNotThrow(() -> job.execute(mock(JobExecutionContext.class)));
    }

    @Test
    void execute_shouldSwallowAlertFailure() {
        IWmsDeviceService deviceService = mock(IWmsDeviceService.class);
        when(deviceService.scanOfflineDevices()).thenReturn(Collections.singletonList(device("DDJ01")));
        IWmsDeviceAlertService alertService = mock(IWmsDeviceAlertService.class);
        when(alertService.alertOffline(anyList(), anyBoolean())).thenThrow(new RuntimeException("公告服务不可用"));
        WmsDeviceOfflineScanJob job = newJob(deviceService, alertService);

        assertDoesNotThrow(() -> job.execute(mock(JobExecutionContext.class)));
    }

    /** 常量必须与 Flyway 种子行的 id 一致，否则 UI 的「立即执行」控制的是另一个 job。 */
    @Test
    void jobConstants_mustBeConsistent() {
        Assertions.assertEquals("1920000000000000102", WmsConstants.DEVICE_SCAN_JOB_ID);
        Assertions.assertEquals(
                "org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob", WmsConstants.DEVICE_SCAN_JOB_CLASS);
        Assertions.assertTrue(
                WmsConstants.DEVICE_SCAN_JOB_CLASS.startsWith("org.jeecg."),
                "类名须以 org.jeecg. 开头：QuartzJobServiceImpl.getClass() 有包白名单防 RCE");
    }
}