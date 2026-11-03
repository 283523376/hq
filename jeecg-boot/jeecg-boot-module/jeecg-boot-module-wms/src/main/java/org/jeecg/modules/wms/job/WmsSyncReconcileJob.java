package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.service.IWmsSyncReconcileService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/**
 * @Description: SB-001 定时对账任务（spec 2026-11-03 §2.2）。
 *
 * <p>每日 23:00 按系统配置 {@code sync.auto.scope} 自动建一个对账批次，操作人固定 {@code system}。
 *
 * <p>三个闸门，少一个都会天天静默跑错口径：
 * <ul>
 *   <li>{@code sync.auto=false}：整轮跳过（手工页面仍可发起对账）；</li>
 *   <li>{@code WAREHOUSE}：定时任务没有仓库上下文，报 ERROR 后跳过，绝不建缺仓库的批次；</li>
 *   <li>水位线**只对 TODAY 生效**：FULL 是「当前全量快照」，传 windowStart 会被
 *       {@code IWmsSyncReconcileService#createRun} 直接拒绝（2026-11-03 契约收紧）。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsSyncReconcileJob implements Job {

    /** 定时任务无登录上下文，操作人固定写 system（与 createRun 的防御性复核同值）。 */
    static final String JOB_OPERATOR = WmsConstants.AUDIT_OPERATOR_SYSTEM;

    /** 「自动同步」开关（sync.auto）：关闭时本轮跳过，改为页面手工对账。 */
    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsSyncReconcileService reconcileService;

    @Autowired
    private WmsSyncRunMapper syncRunMapper;

    /** Quartz 需要可访问的无参构造器（JDBC 存储与「立即执行」）。 */
    public WmsSyncReconcileJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsSyncReconcileJob(IWmsSystemConfigService systemConfigService,
                        IWmsSyncReconcileService reconcileService,
                        WmsSyncRunMapper syncRunMapper) {
        this.systemConfigService = systemConfigService;
        this.reconcileService = reconcileService;
        this.syncRunMapper = syncRunMapper;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanForTest();
    }

    /** 包级可见：单测直调（照 {@code WmsInboundSyncRetryJob.scanAndResyncForTest} 惯例）。 */
    void scanForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），本轮定时对账跳过，请在页面手工发起对账");
            return;
        }
        String scope = resolveScope();
        if (scope == null) {
            return;
        }
        if (WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(scope)) {
            log.error("自动对账范围配置为 WAREHOUSE，但定时任务没有仓库上下文，本轮跳过；"
                    + "请改为 FULL/TODAY，或在页面手工发起指定仓库对账");
            return;
        }

        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType(scope);
        // 水位线只对 TODAY 生效：FULL 必须是全量快照，windowStart 传值会被 createRun 拒绝
        if (WmsConstants.SYNC_SCOPE_TODAY.equals(scope)) {
            dto.setWindowStart(resolveWatermark());
        }

        try {
            String runId = reconcileService.createRun(dto, JOB_OPERATOR);
            log.info("定时对账已提交：批次={}，范围={}，窗口起点={}", runId, scope, dto.getWindowStart());
        } catch (Exception e) {
            // 定时任务不能因为一次建批次失败（如已有批次在执行中）就中断后续调度
            log.error("定时对账提交失败（范围={}）：{}", scope, e.getMessage(), e);
        }
    }

    /**
     * 读 {@code sync.auto.scope} 并归一化。
     *
     * @return FULL/TODAY/WAREHOUSE；配置非法（未知值）返回 null 表示本轮跳过
     */
    String resolveScope() {
        String raw = systemConfigService == null
                ? null
                : systemConfigService.getString(WmsConfigKey.SYNC_AUTO_SCOPE.getKey(),
                        WmsConstants.SYNC_SCOPE_TODAY);
        String scope = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (scope.isEmpty()) {
            log.warn("自动对账范围未配置，按默认 {} 处理", WmsConstants.SYNC_SCOPE_TODAY);
            return WmsConstants.SYNC_SCOPE_TODAY;
        }
        if (!WmsConstants.SYNC_SCOPE_FULL.equals(scope)
                && !WmsConstants.SYNC_SCOPE_TODAY.equals(scope)
                && !WmsConstants.SYNC_SCOPE_WAREHOUSE.equals(scope)) {
            log.error("自动对账范围配置非法（{}），本轮跳过；可选值：FULL/TODAY/WAREHOUSE", raw);
            return null;
        }
        return scope;
    }

    /**
     * 水位线 = 最近一个成功批次的 {@code window_end}；没有成功批次则回落当天 00:00。
     *
     * <p><b>必须限定 scope=FULL/TODAY</b>：页面手工发起的 WAREHOUSE 批次成功后也会写
     * 「成功 + window_end」。若拿它当水位线，其他仓库当天早于该时刻的库存变动就再也不会
     * 被任何批次覆盖——静默漏对账（审查 I-1）。
     *
     * <p>查库失败同样回落当天 00:00：窗口放宽只是多查一点数据，不会漏对账。
     */
    Date resolveWatermark() {
        try {
            WmsSyncRun latest = syncRunMapper.selectOne(Wrappers.<WmsSyncRun>query()
                    .eq("status", WmsConstants.SYNC_RUN_STATUS_SUCCESS)
                    .in("scope_type", WmsConstants.SYNC_SCOPE_FULL, WmsConstants.SYNC_SCOPE_TODAY)
                    .orderByDesc("window_end")
                    .last("limit 1"));
            if (latest != null && latest.getWindowEnd() != null) {
                return latest.getWindowEnd();
            }
        } catch (Exception e) {
            log.warn("查询对账水位线失败，回落当天 00:00：{}", e.getMessage());
        }
        return Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
