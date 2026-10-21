package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Description: 出库补偿任务（CK-001/CK-002）。
 *
 * <p>三件事：1) 重发超时仍「已分配」的下发；2) 续跑出库单同步；3) 续跑销售订单同步。
 * 单条失败不中断整轮。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsOutboundSyncRetryJob implements Job {

    @Autowired
    private IWmsOutboundSyncService syncService;

    @Autowired
    private IWmsOutboundTaskService taskService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsOutboundSyncRetryJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsOutboundSyncRetryJob(IWmsOutboundSyncService syncService, IWmsOutboundTaskService taskService) {
        this.syncService = syncService;
        this.taskService = taskService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanAndResyncForTest();
    }

    void scanAndResyncForTest() {
        redispatchUndispatched();
        resyncCandidates();
        resyncSaleCandidates();
    }

    /** 分配成功但事务提交后进程中断/下发前异常。 */
    private void redispatchUndispatched() {
        Date before = new Date(System.currentTimeMillis()
                - WmsConstants.UNDISPATCHED_OUTBOUND_TIMEOUT_MINUTES * 60_000L);
        List<WmsOutboundOrder> list;
        try {
            list = syncService.selectUndispatchedBefore(before, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("出库未下发补偿取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        int ok = 0;
        for (WmsOutboundOrder order : list) {
            try {
                taskService.dispatch(order.getId());
                ok++;
            } catch (Exception e) {
                log.error("出库未下发补偿处理[{}]异常：{}", order.getOutboundNo(), e.getMessage(), e);
            }
        }
        log.info("出库未下发补偿本轮结束：候选={}，处理={}", list.size(), ok);
    }

    private void resyncCandidates() {
        Date staleBefore = syncService.syncStaleBefore();
        List<WmsOutboundOrder> candidates;
        try {
            candidates = syncService.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("出库同步补偿取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int claimed = 0;
        int success = 0;
        for (WmsOutboundOrder order : candidates) {
            try {
                if (syncService.claimForSync(order.getId(), staleBefore) == 0) {
                    continue;
                }
                claimed++;
                // announce=false：定时任务每 5 分钟一次，避免站内消息轰炸
                WmsOrderSyncAttempt attempt = syncService.sync(order.getId(), false);
                syncService.persistResult(order.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    // 后台不逐次告警，连续失败达到阈值补发一次
                    syncService.announceFailureIfNeeded(order.getId(), false);
                }
            } catch (Exception e) {
                log.error("出库同步补偿处理[{}]异常：{}", order.getOutboundNo(), e.getMessage(), e);
            }
        }
        log.info("出库同步补偿本轮结束：候选={}，认领={}，成功={}", candidates.size(), claimed, success);
    }

    private void resyncSaleCandidates() {
        Date staleBefore = syncService.syncStaleBefore();
        List<WmsOutboundOrder> candidates;
        try {
            candidates = syncService.selectSaleSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("销售订单同步补偿取候选失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int claimed = 0;
        int success = 0;
        for (WmsOutboundOrder order : candidates) {
            try {
                if (syncService.claimSaleForSync(order.getId(), staleBefore) == 0) {
                    continue;
                }
                claimed++;
                WmsOrderSyncAttempt attempt = syncService.syncSaleOrder(order.getId(), false);
                syncService.persistSaleResult(order.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    syncService.announceFailureIfNeeded(order.getId(), true);
                }
            } catch (Exception e) {
                log.error("销售订单同步补偿处理[{}]异常：{}", order.getSalesOrderNo(), e.getMessage(), e);
            }
        }
        log.info("销售订单同步补偿本轮结束：候选={}，认领={}，成功={}", candidates.size(), claimed, success);
    }
}
