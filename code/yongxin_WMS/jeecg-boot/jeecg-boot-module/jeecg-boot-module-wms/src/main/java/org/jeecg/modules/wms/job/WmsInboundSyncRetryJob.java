package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.jeecg.modules.wms.service.IWmsInboundSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Description: 入库单 ERP 同步补偿任务（RK-001 异常分支「同步失败自动重试」）。
 *
 * <p>扫描「已审核」且同步失败 / 待同步 / 同步中已僵死的入库单，自动续跑同步。
 * 与订单补偿任务同构：依赖接口、包级 scanAndResyncForTest 便于单测、单条失败不中断整轮。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsInboundSyncRetryJob implements Job {

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsInboundService inboundService;

    @Autowired
    private IWmsInboundSyncService inboundSyncService;

    /** Quartz 需要可访问的无参构造器（JDBC 存储与「立即执行」）。 */
    public WmsInboundSyncRetryJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsInboundSyncRetryJob(IWmsInboundService inboundService, IWmsInboundSyncService inboundSyncService) {
        this.inboundService = inboundService;
        this.inboundSyncService = inboundSyncService;
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
        Date staleBefore = inboundService.syncStaleBefore();
        List<WmsInboundOrder> candidates;
        try {
            candidates = inboundService.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("入库补偿任务取候选单据失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("入库补偿任务本轮无待处理单据");
            return;
        }
        int claimed = 0;
        int success = 0;
        int failed = 0;
        for (WmsInboundOrder order : candidates) {
            try {
                int affected = inboundService.claimForSync(order.getId(), staleBefore);
                if (affected == 0) {
                    continue;
                }
                claimed++;
                // announce=false：定时任务每 5 分钟跑一次，发站内消息会轰炸用户
                WmsOrderSyncAttempt attempt = inboundSyncService.syncInbound(order, false);
                inboundService.persistSyncResult(order.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("入库补偿任务处理单据[{}]异常：{}", order.getOrderNo(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("入库补偿任务本轮结束：候选={}，认领成功={}，同步成功={}，同步失败={}",
                candidates.size(), claimed, success, failed);
    }
}
