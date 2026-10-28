package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsOperationReportDraftService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;

/**
 * @Description: 工序汇报 → 半成品入库草稿 定时同步。
 *
 * <p>**不做游标表**：金蝶工序汇报允许补录/迟交，记「最后同步时间」会漏单。
 * 改用「滚动窗口 + 幂等 upsert」——每次回看 N 天（默认 3 天），
 * 已落过的分录按 `erp_entry_key` 更新而不是重复建单（见 {@code WmsOperationReportDraftServiceImpl}）。
 *
 * <p>开关：`sync.auto=false` 时本轮跳过（与其它同步任务一致，改配置下一次调度即生效）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsOperationReportSyncJob implements Job {

    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsOperationReportDraftService draftService;

    @Autowired(required = false)
    private IWmsKingdeeAuthService authService;

    /** 回看天数（含当天）。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.lookback-days:3}")
    private int lookbackDays = 3;

    /** 单轮最多处理行数。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.max-rows:2000}")
    private int maxRows = 2000;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsOperationReportSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsOperationReportSyncJob(IWmsOperationReportDraftService draftService,
                              IWmsSystemConfigService systemConfigService,
                              IWmsKingdeeAuthService authService) {
        this.draftService = draftService;
        this.systemConfigService = systemConfigService;
        this.authService = authService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    void syncForTest() {
        if (WmsSyncAutoGate.isManualOnly(systemConfigService)) {
            log.info("自动同步已关闭（sync.auto=false），工序汇报同步本轮跳过，请在业务页面手工触发");
            return;
        }
        if (authService != null && !authService.isConfigured()) {
            log.warn("工序汇报同步跳过：金蝶未配置（base-url/app-id/app-secret）");
            return;
        }
        String end = LocalDate.now().toString();
        String start = LocalDate.now().minusDays(Math.max(lookbackDays, 1) - 1L).toString();
        try {
            WmsOperationReportSyncResultVO vo = draftService.ingest(start, end, maxRows);
            log.info("工序汇报同步完成（{} ~ {}）：拉取 {}，新建 {}，更新 {}，跳过 {}",
                    start, end, vo.getFetched(), vo.getCreated(), vo.getUpdated(), vo.getSkipped());
        } catch (Exception e) {
            // 单轮失败不影响应用与其它任务，下一轮窗口会覆盖本次遗漏
            log.error("工序汇报同步失败（{} ~ {}）：{}", start, end, e.getMessage(), e);
        }
    }
}
