package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncLog;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncLogMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.function.IntSupplier;

/**
 * @Description: SB-001 对账数据保留期清理（spec 2026-11-03 §2.9.1）。
 *
 * <p>每日 03:20 执行，与审计清理（03:30）、导出清理（03:00）错峰。
 * 四张对账表按 {@code sync.retention.days} / {@code sync.retention.diff-days} 物理删除，
 * {@code wms_sync_log} 按 {@code sync.log.retention.days} 单独删除。
 *
 * <p><b>配置非法（≤0 / 非数字）时该组一条都不删并报 ERROR</b>：照删会把对账证据一次性清空。
 * 这里刻意**不用 {@code getInt}**——它对非法值静默回落到兜底值，等于替用户做了删除决定。
 *
 * <p>删除顺序固定 <b>snapshot → flow → diff → run</b>（子先父后），否则删完批次会留下孤儿行。
 * 分批 + 批数上限，避免长事务持锁拖垮对账写入。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsSyncRetentionJob implements Job {

    /** 每批删除条数：一次删太多会长时间持锁，对账表又是周期性批量写入的。 */
    public static final int BATCH_SIZE = 5000;

    /** 单次执行每张表最多删这么多批，防止异常情况下无限循环。 */
    private static final int MAX_BATCHES = 200;

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    @Autowired
    private WmsSyncSnapshotMapper snapshotMapper;

    @Autowired
    private WmsSyncFlowMapper flowMapper;

    @Autowired
    private WmsSyncDiffMapper diffMapper;

    @Autowired
    private WmsSyncRunMapper syncRunMapper;

    @Autowired
    private WmsSyncLogMapper syncLogMapper;

    @Autowired(required = false)
    private IWmsSystemConfigService systemConfigService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsSyncRetentionJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsSyncRetentionJob(WmsSyncSnapshotMapper snapshotMapper,
                        WmsSyncFlowMapper flowMapper,
                        WmsSyncDiffMapper diffMapper,
                        WmsSyncRunMapper syncRunMapper,
                        WmsSyncLogMapper syncLogMapper,
                        IWmsSystemConfigService systemConfigService) {
        this.snapshotMapper = snapshotMapper;
        this.flowMapper = flowMapper;
        this.diffMapper = diffMapper;
        this.syncRunMapper = syncRunMapper;
        this.syncLogMapper = syncLogMapper;
        this.systemConfigService = systemConfigService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        scanForTest();
    }

    /** 包级可见：单测直调（照 {@code WmsAuditCleanJob#clean} 惯例）。 */
    void scanForTest() {
        int retentionDays = resolveRetentionDays(WmsConfigKey.SYNC_RETENTION_DAYS);
        int diffRetentionDays = resolveRetentionDays(WmsConfigKey.SYNC_RETENTION_DIFF_DAYS);
        int logRetentionDays = resolveRetentionDays(WmsConfigKey.SYNC_LOG_RETENTION_DAYS);

        int snapshots = 0;
        int flows = 0;
        int runs = 0;
        if (retentionDays > 0) {
            // 快照/流水/批次同一保留期，用同一个 cutoff，避免三张表删出时间差
            Date cutoff = cutoffOf(retentionDays);
            snapshots = deleteInBatches(() -> snapshotMapper.delete(
                    new LambdaQueryWrapper<WmsSyncSnapshot>()
                            .lt(WmsSyncSnapshot::getCreateTime, cutoff)
                            .last("LIMIT " + BATCH_SIZE)));
            flows = deleteInBatches(() -> flowMapper.delete(
                    new LambdaQueryWrapper<WmsSyncFlow>()
                            .lt(WmsSyncFlow::getCreateTime, cutoff)
                            .last("LIMIT " + BATCH_SIZE)));
        }

        int diffs = 0;
        if (diffRetentionDays > 0) {
            Date diffCutoff = cutoffOf(diffRetentionDays);
            diffs = deleteInBatches(() -> diffMapper.delete(
                    new LambdaQueryWrapper<WmsSyncDiff>()
                            .lt(WmsSyncDiff::getCreateTime, diffCutoff)
                            .last("LIMIT " + BATCH_SIZE)));
        }

        // 子表（snapshot/flow/diff）删完才删批次，避免留下孤儿行
        if (retentionDays > 0) {
            Date cutoff = cutoffOf(retentionDays);
            runs = deleteInBatches(() -> syncRunMapper.delete(
                    new LambdaQueryWrapper<WmsSyncRun>()
                            .lt(WmsSyncRun::getCreateTime, cutoff)
                            .last("LIMIT " + BATCH_SIZE)));
        }

        int logs = 0;
        if (logRetentionDays > 0) {
            Date logCutoff = cutoffOf(logRetentionDays);
            logs = deleteInBatches(() -> syncLogMapper.delete(
                    new LambdaQueryWrapper<WmsSyncLog>()
                            .lt(WmsSyncLog::getCreateTime, logCutoff)
                            .last("LIMIT " + BATCH_SIZE)));
        }

        if (snapshots + flows + diffs + runs + logs > 0) {
            log.info("对账数据清理完成：快照={}，流水={}，差异={}，批次={}，同步日志={}", snapshots, flows,
                    diffs, runs, logs);
        }
    }

    /**
     * 读保留期天数：配置缺失用枚举默认值；解析失败或 ≤0 视为非法（返回 -1，调用方跳过该组删除）。
     */
    int resolveRetentionDays(WmsConfigKey key) {
        if (systemConfigService == null) {
            return Integer.parseInt(key.getDefaultValue());
        }
        String raw = systemConfigService.getString(key.getKey(), key.getDefaultValue());
        if (raw == null || raw.trim().isEmpty()) {
            log.error("{}未配置（{}），本轮跳过该组清理", key.getLabel(), key.getKey());
            return -1;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            if (days <= 0) {
                log.error("{}配置为 {}（{}，必须大于 0），本轮跳过该组清理，避免把全部数据删光",
                        key.getLabel(), days, key.getKey());
                return -1;
            }
            return days;
        } catch (NumberFormatException e) {
            log.error("{}配置非法（{}={}），本轮跳过该组清理", key.getLabel(), key.getKey(), raw);
            return -1;
        }
    }

    private Date cutoffOf(int retentionDays) {
        return new Date(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY);
    }

    /** 分批删到删不满一批为止；返回实际删除条数。 */
    private int deleteInBatches(IntSupplier batchDeleter) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = batchDeleter.getAsInt();
            if (deleted <= 0) {
                break;
            }
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
