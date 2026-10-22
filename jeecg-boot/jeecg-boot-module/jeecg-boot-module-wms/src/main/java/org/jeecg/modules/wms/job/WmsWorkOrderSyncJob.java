package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * @Description: 生产工单 ERP 同步补偿任务（BD-002 V2.2）。两阶段：
 * ①增量窗口拉取（上次成功时间-30min → 现在，无记录则最近 7 天）；
 * ②近 7 天同步失败且 retry_count&lt;3 的单号逐单重试（评审 I2，解决时间窗推进死角）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsWorkOrderSyncJob implements Job {

    @Autowired
    private IWmsKingdeeWorkOrderSyncService kingdeeSyncService;

    @Autowired
    private IWmsSyncLogService syncLogService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsWorkOrderSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsWorkOrderSyncJob(IWmsKingdeeWorkOrderSyncService kingdeeSyncService,
                        IWmsSyncLogService syncLogService) {
        this.kingdeeSyncService = kingdeeSyncService;
        this.syncLogService = syncLogService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    void syncForTest() {
        try {
            // ①增量窗口（窗口计算依赖 wms_sync_log 最近成功时间，由同步服务内记录；
            //   Job 侧用默认 7 天窗口兜底，重叠由 upsert 幂等吸收）
            WmsKingdeeWorkOrderSyncResultVO vo = kingdeeSyncService.syncFromKingdee(null, null, null, 50);
            int retried = 0;
            // ②失败单重试
            List<String> failed = syncLogService.listFailedObjectNos(
                    WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, 3, 7);
            for (String billNo : failed) {
                if (kingdeeSyncService.syncSingleFromKingdee(billNo)) {
                    retried++;
                }
            }
            log.info("工单ERP同步任务：窗口查询{}行，新增{}，更新{}，跳过{}；失败单重试成功{}",
                    vo.getQueried(), vo.getImported(), vo.getUpdated(), vo.getSkipped(), retried);
        } catch (Exception e) {
            log.error("工单ERP同步任务执行失败：{}", e.getMessage(), e);
        }
    }
}
