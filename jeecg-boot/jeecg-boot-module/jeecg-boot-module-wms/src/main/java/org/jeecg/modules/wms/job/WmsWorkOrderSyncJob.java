package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * @Description: 生产工单 ERP 同步补偿任务（BD-002 V2.2）。三阶段：
 * ①按「最近成功时间-30min → 今天」逐日窗口增量拉取（每日 200 行，避免单批 50 行漏单）；
 * ②近 7 天失败且 retry_count≤3 的单号逐单重试（评审 I2，解决时间窗推进死角）；
 * ③连续失败达到 3 次的单号发一次站内告警（评审 I3）。
 *
 * <p>金蝶未配置（isConfigured=false）时直接跳过，不写失败日志（评审 I9）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsWorkOrderSyncJob implements Job {

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsKingdeeWorkOrderSyncService kingdeeSyncService;

    @Autowired
    private IWmsSyncLogService syncLogService;

    @Autowired
    private IWmsKingdeeAuthService kingdeeAuthService;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.alert-to-user:admin}")
    private String alertToUser;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsWorkOrderSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsWorkOrderSyncJob(IWmsKingdeeWorkOrderSyncService kingdeeSyncService,
                        IWmsSyncLogService syncLogService,
                        IWmsKingdeeAuthService kingdeeAuthService,
                        ISysBaseAPI sysBaseAPI) {
        this.kingdeeSyncService = kingdeeSyncService;
        this.syncLogService = syncLogService;
        this.kingdeeAuthService = kingdeeAuthService;
        this.sysBaseAPI = sysBaseAPI;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    void syncForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），本轮跳过，请在业务页面手工同步");
            return;
        }

        if (kingdeeAuthService != null && !kingdeeAuthService.isConfigured()) {
            log.warn("工单ERP同步任务跳过：金蝶未配置（wms.kingdee.base-url/app-id 等）");
            return;
        }
        // ①增量窗口：最近成功时间 - 30min（无成功记录则最近 7 天），逐日切片，避免单批漏单
        try {
            long lastSuccess = syncLogService.latestSuccessTimeMillis(WmsConstants.SYNC_LOG_TYPE_WORK_ORDER);
            LocalDate end = LocalDate.now();
            LocalDate start = lastSuccess > 0
                    ? Instant.ofEpochMilli(lastSuccess - 30L * 60L * 1000L)
                        .atZone(ZoneId.systemDefault()).toLocalDate()
                    : end.minusDays(6);
            int totalQueried = 0;
            for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
                try {
                    WmsKingdeeWorkOrderSyncResultVO vo = kingdeeSyncService.syncFromKingdee(
                            null, day.toString(), day.toString(), 200);
                    totalQueried += vo.getQueried();
                } catch (Exception e) {
                    // 单日窗口失败不阻断其余窗口（评审 Minor6）
                    log.error("工单ERP同步任务窗口[{}]失败：{}", day, e.getMessage(), e);
                }
            }
            log.info("工单ERP同步任务：增量窗口[{} ~ {}]，查询{}行", start, end, totalQueried);
        } catch (Exception e) {
            log.error("工单ERP同步任务增量阶段失败：{}", e.getMessage(), e);
        }

        // ②失败单重试：逐条隔离，单条异常不中断其余单号
        List<String> failed = safeList(() -> syncLogService.listFailedObjectNos(
                WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, WmsConstants.SYNC_MAX_RETRY, 7));
        int retried = 0;
        for (String billNo : failed) {
            try {
                if (kingdeeSyncService.syncSingleFromKingdee(billNo)) {
                    retried++;
                }
            } catch (Exception e) {
                log.error("工单[{}]失败重试异常：{}", billNo, e.getMessage(), e);
            }
        }
        log.info("工单ERP同步任务：失败单重试 {} 条，成功 {}", failed.size(), retried);

        // ③连续失败告警：仅在 retry_count 恰好达到上限时发一次
        List<String> exhausted = safeList(() -> syncLogService.listExhaustedObjectNos(
                WmsConstants.SYNC_LOG_TYPE_WORK_ORDER, WmsConstants.SYNC_MAX_RETRY, 7));
        for (String billNo : exhausted) {
            alert(billNo);
        }
    }

    private <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.error("工单ERP同步任务查询失败单异常：{}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    private void alert(String billNo) {
        try {
            String content = String.format("工单号：%s；连续失败次数：%d；请检查金蝶单据/物料映射/接口配置后手工重试",
                    billNo, WmsConstants.SYNC_MAX_RETRY);
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.WORK_ORDER_SYNC_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("工单[{}]同步失败告警投递失败：{}", billNo, e.getMessage(), e);
        }
    }
}
