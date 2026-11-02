package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * @Description: 审计日志保留期清理（XT-002）。
 *
 * <p>保留期由前端系统配置项 {@code audit.retention.days} 控制（默认 365 天）。
 * 到期**直接删除**，不归档——归档要另定冷存储与查询入口，成本远超日志表本身的价值。
 *
 * <p><b>配置非法（0 / 负数 / 非数字）时一条都不删并报 ERROR</b>：
 * 把保留期手滑写成 0，最坏结果是日志表涨到几个 G；照删则是审计证据一次性全没。
 * 注意这里**刻意不用 {@code getInt}**——它对 ≤0 会静默回落到兜底值，那等于替用户做了删除决定。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsAuditCleanJob implements Job {

    /** 每批删除条数：一次删太多会长时间持锁，审计表又是持续写入的。 */
    public static final int BATCH_SIZE = 5000;

    /** 单次执行最多删这么多批，防止异常情况下无限循环。 */
    private static final int MAX_BATCHES = 200;

    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;

    @Autowired
    private WmsAuditLogMapper auditLogMapper;

    @Autowired
    private IWmsSystemConfigService systemConfigService;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsAuditCleanJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsAuditCleanJob(WmsAuditLogMapper auditLogMapper, IWmsSystemConfigService systemConfigService) {
        this.auditLogMapper = auditLogMapper;
        this.systemConfigService = systemConfigService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        clean();
    }

    /**
     * 按保留期分批清理。
     *
     * @return 实际删除条数；配置非法时为 0
     */
    public int clean() {
        int retentionDays = resolveRetentionDays();
        if (retentionDays <= 0) {
            return 0;
        }
        Date cutoff = new Date(System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = auditLogMapper.delete(new LambdaQueryWrapper<WmsAuditLog>()
                    .lt(WmsAuditLog::getCreateTime, cutoff)
                    .last("LIMIT " + BATCH_SIZE));
            if (deleted <= 0) {
                break;
            }
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("审计日志清理完成：删除 {} 条（保留期 {} 天）", total, retentionDays);
        }
        return total;
    }

    /**
     * 读保留期天数：读不到就用默认 365；解析失败或 ≤0 视为非法。
     */
    int resolveRetentionDays() {
        String raw = systemConfigService.getString(WmsConfigKey.AUDIT_RETENTION_DAYS.getKey(), "365");
        if (raw == null || raw.trim().isEmpty()) {
            log.warn("审计日志保留天数未配置，按默认 365 天处理");
            return 365;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            if (days <= 0) {
                log.error("审计日志保留天数为 {}（必须大于 0），已跳过清理，避免把全部审计日志删光", days);
                return -1;
            }
            return days;
        } catch (NumberFormatException e) {
            log.error("审计日志保留天数配置非法（{}），已跳过清理", raw);
            return -1;
        }
    }
}
