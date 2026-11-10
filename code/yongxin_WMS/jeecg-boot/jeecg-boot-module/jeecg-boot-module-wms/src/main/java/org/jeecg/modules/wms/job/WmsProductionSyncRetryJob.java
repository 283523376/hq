package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.service.IWmsProductionTaskService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Description: 生产入库补偿任务（RK-002）。
 *
 * <p>两件事：1) 重新下发超过阈值仍「未下发」的任务（JVM 崩溃自愈）；
 * 2) 续跑「已入库」但同步待处理的记录。单条失败不中断整轮。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsProductionSyncRetryJob implements Job {

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsProductionInboundService inboundService;

    @Autowired
    private IWmsProductionSyncService productionSyncService;

    @Autowired
    private IWmsProductionTaskService taskService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsProductionSyncRetryJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsProductionSyncRetryJob(IWmsProductionInboundService inboundService,
                              IWmsProductionSyncService productionSyncService,
                              IWmsProductionTaskService taskService) {
        this(inboundService, productionSyncService, taskService, null);
    }

    /** 仅供单测注入依赖（含自动同步开关）。 */
    WmsProductionSyncRetryJob(IWmsProductionInboundService inboundService,
                              IWmsProductionSyncService productionSyncService,
                              IWmsProductionTaskService taskService,
                              IWmsSystemConfigService systemConfigService) {
        this.inboundService = inboundService;
        this.productionSyncService = productionSyncService;
        this.taskService = taskService;
        this.systemConfigService = systemConfigService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanAndResyncForTest();
    }

    void scanAndResyncForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），本轮跳过，请在业务页面手工同步");
            return;
        }

        redispatchUndispatched();
        resyncCandidates();
        reconcileReportSourced();
    }

    /**
     * 方案 B 对账：工序汇报来源的入库单等车间在金蝶「汇报入库」，
     * 命中则回填 ERP 生产入库单号。未命中属正常等待，不计失败、不告警。
     */
    private void reconcileReportSourced() {
        List<WmsProductionInbound> list;
        try {
            list = inboundService.selectReportSourcedPendingReconcile(WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("工序汇报入库对账取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        int ok = 0;
        for (WmsProductionInbound record : list) {
            try {
                WmsOrderSyncAttempt attempt = productionSyncService.reconcileReportSourced(record);
                if (attempt.isSuccess()) {
                    inboundService.persistSyncResult(record.getId(), attempt);
                    ok++;
                }
            } catch (Exception e) {
                log.error("工序汇报入库对账[{}]异常：{}", record.getInboundNo(), e.getMessage(), e);
            }
        }
        log.info("工序汇报入库对账本轮结束：候选={}，对账成功={}", list.size(), ok);
    }

    /** 未下发补偿：提交成功但事务提交后进程中断/下发前异常。 */
    private void redispatchUndispatched() {
        Date before = new Date(System.currentTimeMillis()
                - WmsConstants.UNDISPATCHED_TIMEOUT_MINUTES * 60_000L);
        List<WmsProductionInbound> list;
        try {
            list = inboundService.selectUndispatchedBefore(before, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("生产入库未下发补偿取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        int ok = 0;
        for (WmsProductionInbound record : list) {
            try {
                taskService.dispatchInbound(record.getId());
                ok++;
            } catch (Exception e) {
                log.error("生产入库未下发补偿处理[{}]异常：{}", record.getInboundNo(), e.getMessage(), e);
            }
        }
        log.info("生产入库未下发补偿本轮结束：候选={}，处理={}", list.size(), ok);
    }

    /** 同步补偿：只处理已入库且待同步/失败/僵死的记录。 */
    private void resyncCandidates() {
        Date staleBefore = inboundService.syncStaleBefore();
        List<WmsProductionInbound> candidates;
        try {
            candidates = inboundService.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("生产入库同步补偿取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("生产入库同步补偿本轮无待处理记录");
            return;
        }
        int claimed = 0;
        int success = 0;
        int failed = 0;
        for (WmsProductionInbound record : candidates) {
            try {
                if (inboundService.claimForSync(record.getId(), staleBefore) == 0) {
                    continue;
                }
                claimed++;
                // announce=false：定时任务每 5 分钟一次，避免站内消息轰炸
                WmsOrderSyncAttempt attempt = productionSyncService.syncInbound(record.getId(), false);
                inboundService.persistSyncResult(record.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    failed++;
                    // 后台同步默认不逐轮告警；累计失败达到阈值时告警一次
                    WmsProductionInbound updated = inboundService.getById(record.getId());
                    if (updated != null && updated.getSyncFailCount() != null
                            && updated.getSyncFailCount() == WmsConstants.SYNC_MAX_RETRY) {
                        productionSyncService.alertRepeatedFailure(updated, attempt.getErrorMessage());
                    }
                }
            } catch (Exception e) {
                log.error("生产入库同步补偿处理[{}]异常：{}", record.getInboundNo(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("生产入库同步补偿本轮结束：候选={}，认领={}，成功={}，失败={}",
                candidates.size(), claimed, success, failed);
    }
}
