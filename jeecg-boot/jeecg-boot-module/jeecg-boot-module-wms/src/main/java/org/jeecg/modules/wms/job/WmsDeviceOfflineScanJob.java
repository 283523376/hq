package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 设备离线扫描任务：把心跳静默超时的设备置为离线并聚合告警。
 *
 * <p>职责刻意切开：**跃迁与日志**由 {@link IWmsDeviceService#scanOfflineDevices()} 负责，
 * **告警投递**由本类编排 —— 这样两者可分别测试。
 *
 * <p>{@code @DisallowConcurrentExecution}：单轮处理上限 {@code DEVICE_SCAN_BATCH} 条，
 * 正常情况下远快于 5 分钟周期；加此注解是防极端堆积时自我重叠。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsDeviceOfflineScanJob implements Job {

    @Autowired
    private IWmsDeviceService wmsDeviceService;

    @Autowired
    private IWmsDeviceAlertService wmsDeviceAlertService;

    @Override
    public void execute(JobExecutionContext context) {
        log.info("WMS设备离线扫描任务开始");
        try {
            List<WmsDevice> turned = wmsDeviceService.scanOfflineDevices();
            if (turned.isEmpty()) {
                log.info("WMS设备离线扫描任务结束：本轮无设备转为离线");
                return;
            }
            // 扫描只捕捉跃迁（WHERE status != 离线），天然一台设备一轮只告警一次；
            // 仍传 respectThrottle=true，以覆盖「恢复在线后 30 分钟内又失联」这种反复。
            List<String> alerted = wmsDeviceAlertService.alertOffline(turned, true);
            log.info("WMS设备离线扫描任务结束：转离线 {} 台，实际告警 {} 台",
                    turned.size(), alerted.size());
        } catch (Exception e) {
            // 不得抛回调度器：Quartz 会把 job 置为 ERROR，后续轮次可能不再触发，
            // 「离线告警」整条链路会静默死掉。
            log.error("WMS设备离线扫描任务异常：{}", e.getMessage(), e);
        }
    }
}