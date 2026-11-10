package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.service.IWmsOrderService;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @Description: WMS 订单同步补偿任务。
 *
 * <p>扫描「已下达」且同步失败 / 待同步 / 同步中已僵死的订单，自动续跑同步，实现自愈。
 * 加 {@link DisallowConcurrentExecution} 防止单轮耗时超过周期时自我重叠。
 *
 * <p>依赖的都是**接口**（{@link IWmsOrderService} / {@link IWmsOrderSyncService}），
 * 不直接操作 Mapper——候选查询、认领、结果落库都经由订单服务，保持「谁拥有数据谁写」，
 * 也避免落库逻辑在订单服务与定时任务两处各写一遍。
 *
 * <p>注意：本任务**不代用户下达**——「已审核未下达」的订单不会被自动下达，下达是人的决策。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsOrderSyncRetryJob implements Job {

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工同步。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsOrderService orderService;

    @Autowired
    private IWmsOrderSyncService orderSyncService;

    /**
     * Quartz 的 JDBC 存储与「定时任务管理 → 立即执行」需要一个**可访问的无参构造器**来实例化 Job；
     * 只提供带参构造器会在运行时抛 NoSuchMethodException，表现为「立即执行失败」。
     * 依赖改为字段注入（与本项目 {@code UserUpadtePwdJob} 的既有写法一致）。
     */
    public WmsOrderSyncRetryJob() {
    }

    /** 仅供单测注入依赖，避免反射塞字段。 */
    WmsOrderSyncRetryJob(IWmsOrderService orderService, IWmsOrderSyncService orderSyncService) {
        this.orderService = orderService;
        this.orderSyncService = orderSyncService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanAndResyncForTest();
    }

    /**
     * 一轮扫描与续跑。可见性放宽到包级，便于单测直接驱动。
     */
    void scanAndResyncForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），本轮跳过，请在业务页面手工同步");
            return;
        }

        // 阈值口径由订单服务统一提供，避免两处设定漂移
        Date staleBefore = orderService.syncStaleBefore();
        List<WmsOrder> candidates;
        try {
            candidates = orderService.selectSyncCandidates(staleBefore, WmsConstants.SYNC_SCAN_BATCH);
        } catch (Exception e) {
            log.error("补偿任务取候选订单失败：{}", e.getMessage(), e);
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.info("补偿任务本轮无待处理订单");
            return;
        }
        int claimed = 0;
        int success = 0;
        int failed = 0;
        for (WmsOrder order : candidates) {
            try {
                int affected = orderService.claimForSync(order.getId(), staleBefore);
                if (affected == 0) {
                    // 已被用户下达或上一轮处理中，跳过即可，不是错误
                    continue;
                }
                claimed++;
                // announce=false：定时任务每 5 分钟跑一次，发站内消息会轰炸用户
                WmsOrderSyncAttempt attempt = orderSyncService.syncOrder(order, false);
                orderService.persistSyncResult(order.getId(), attempt);
                if (attempt.isSuccess()) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                // 单条失败不得中断整轮
                log.error("补偿任务处理订单[{}]异常：{}", order.getOrderNo(), e.getMessage(), e);
                failed++;
            }
        }
        log.info("补偿任务本轮结束：候选={}，认领成功={}，同步成功={}，同步失败={}",
                candidates.size(), claimed, success, failed);
    }
}
