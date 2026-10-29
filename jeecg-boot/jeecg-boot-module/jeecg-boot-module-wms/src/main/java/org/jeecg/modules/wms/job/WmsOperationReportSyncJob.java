package org.jeecg.modules.wms.job;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.service.IWmsOperationReportDraftService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.util.WmsSyncAutoGate;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @Description: 工序汇报 → 半成品入库草稿 定时同步。
 *
 * <p><b>增量口径（2026-10-29 定）：水位线 + 回看兜底。</b>
 * <ul>
 *   <li>按金蝶 {@code FModifyDate}（最后修改时间）过滤，<b>不是</b> {@code FDate}（业务日期）——
 *       业务日期过滤会让「迟审/补录」的单据在窗口滚过后永久丢失；</li>
 *   <li>起点 = 上次成功同步时间 − 回看天数（默认 1 天），最多回溯 {@code max-window-days}（默认 30 天）防失控；</li>
 *   <li>无水位线（首次运行/日志被清）时退化为回看 {@code initial-days}（默认 7 天）；</li>
 *   <li>水位线取 {@code wms_sync_log} 里该对象类型最近一条成功日志，<b>失败不推进</b>，下一轮自动覆盖。</li>
 * </ul>
 *
 * <p>幂等：同一分录靠 {@code erp_entry_key}（单号#分录序号#工序号）更新而非重复建单。
 *
 * <p>开关：`sync.auto=false` 时本轮跳过（与其它同步任务一致，改配置下一次调度即生效）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@DisallowConcurrentExecution
public class WmsOperationReportSyncJob implements Job {

    /** 同步日志里的对象类型，也是水位线的来源。 */
    private static final String SYNC_OBJECT_TYPE = "OPERATION_REPORT";
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    @Autowired
    private IWmsOperationReportDraftService draftService;

    @Autowired(required = false)
    private IWmsKingdeeAuthService authService;

    @Autowired(required = false)
    private IWmsSyncLogService syncLogService;

    /** 水位线之前再回看的天数（防边界漏单，默认 1 天）。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.backfill-days:1}")
    private int backfillDays = 1;

    /** 单轮最多回溯天数，防止水位线过旧导致一次拉爆（默认 30 天）。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.max-window-days:30}")
    private int maxWindowDays = 30;

    /** 没有水位线时的初始回看天数（默认 7 天）。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.initial-days:7}")
    private int initialDays = 7;

    /** 单轮最多处理行数。 */
    /** 单轮最多处理行数（一天几千条，初始窗口 7 天需要覆盖得住）。 */
    @Value("${wms.sync.erp.kingdee.master-data.operation-report.max-rows:20000}")
    private int maxRows = 20000;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsOperationReportSyncJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsOperationReportSyncJob(IWmsOperationReportDraftService draftService,
                              IWmsSystemConfigService systemConfigService,
                              IWmsKingdeeAuthService authService) {
        this(draftService, systemConfigService, authService, null);
    }

    /** 仅供单测注入依赖。 */
    WmsOperationReportSyncJob(IWmsOperationReportDraftService draftService,
                              IWmsSystemConfigService systemConfigService,
                              IWmsKingdeeAuthService authService,
                              IWmsSyncLogService syncLogService) {
        this.draftService = draftService;
        this.systemConfigService = systemConfigService;
        this.authService = authService;
        this.syncLogService = syncLogService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        syncForTest();
    }

    /**
     * 计算本轮拉取窗口（左闭右闭，格式 yyyy-MM-dd HH:mm:ss）。
     *
     * @param lastSuccess    上次成功同步时间；null = 无水位线
     * @param now            当前时间
     * @param backfillDays   水位线再往前回看的天数
     * @param maxWindowDays  最多回溯天数
     * @param initialDays    无水位线时的初始回看天数
     * @return [start, end]
     */
    static String[] window(Date lastSuccess, Date now, int backfillDays, int maxWindowDays, int initialDays) {
        long dayMs = 24L * 60 * 60 * 1000;
        long startMs;
        if (lastSuccess == null) {
            startMs = now.getTime() - Math.max(initialDays, 1) * dayMs;
        } else {
            startMs = lastSuccess.getTime() - Math.max(backfillDays, 0) * dayMs;
        }
        long floor = now.getTime() - Math.max(maxWindowDays, 1) * dayMs;
        if (startMs < floor) {
            startMs = floor;
        }
        SimpleDateFormat f = new SimpleDateFormat(DATE_TIME_PATTERN);
        return new String[]{f.format(new Date(startMs)), f.format(now)};
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
        Date now = new Date();
        Date watermark = null;
        if (syncLogService != null) {
            long ms = syncLogService.latestSuccessTimeMillis(SYNC_OBJECT_TYPE);
            watermark = ms > 0 ? new Date(ms) : null;
        }
        String[] win = window(watermark, now, backfillDays, maxWindowDays, initialDays);
        String start = win[0];
        String end = win[1];
        try {
            WmsOperationReportSyncResultVO vo = draftService.ingest(start, end, maxRows);
            log.info("工序汇报同步完成（{} ~ {}，水位线窗口）：拉取 {}，新建 {}，更新 {}，跳过 {}",
                    start, end, vo.getFetched(), vo.getCreated(), vo.getUpdated(), vo.getSkipped());
        } catch (Exception e) {
            // 单轮失败不影响应用与其它任务，下一轮窗口会覆盖本次遗漏
            log.error("工序汇报同步失败（{} ~ {}）：{}", start, end, e.getMessage(), e);
        }
    }
}
