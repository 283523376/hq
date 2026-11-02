package org.jeecg.modules.wms.exporter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecgframework.poi.handler.inter.IExcelExportServer;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 审计日志导出的分批数据源（XT-002）。
 *
 * <p>与台账导出一致：用「上一批末行」当游标（create_time + id），**不用 OFFSET**。
 * 审计表只增不改，游标天然稳定。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
public class WmsAuditExportPageSource implements IExcelExportServer {

    private final WmsAuditLogMapper auditLogMapper;
    private final WmsAuditQueryDTO query;
    private final int batchSize;
    private final int maxRows;

    private Date lastTime;
    private String lastId;
    private long exported;

    public WmsAuditExportPageSource(WmsAuditLogMapper auditLogMapper, WmsAuditQueryDTO query,
                                    int batchSize, int maxRows) {
        this.auditLogMapper = auditLogMapper;
        this.query = query;
        this.batchSize = batchSize;
        this.maxRows = maxRows;
    }

    @Override
    public List<Object> selectListForExcelExport(Object queryParams, int page) {
        LambdaQueryWrapper<WmsAuditLog> wrapper = filter(query);
        if (lastTime != null) {
            // 同一秒可能有多条，用 id 做次级游标
            wrapper.and(w -> w.gt(WmsAuditLog::getCreateTime, lastTime)
                    .or(n -> n.eq(WmsAuditLog::getCreateTime, lastTime)
                            .gt(WmsAuditLog::getId, lastId)));
        }
        wrapper.orderByAsc(WmsAuditLog::getCreateTime).orderByAsc(WmsAuditLog::getId)
                .last("LIMIT " + batchSize);
        List<WmsAuditLog> rows = auditLogMapper.selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (exported + rows.size() > maxRows) {
            throw new IllegalStateException("导出行数超过 Excel 单表上限（" + maxRows
                    + " 行），请缩小查询条件后分批导出");
        }
        exported += rows.size();
        WmsAuditLog last = rows.get(rows.size() - 1);
        lastTime = last.getCreateTime();
        lastId = last.getId();
        return new ArrayList<Object>(rows);
    }

    /** 查询条件 → wrapper；导出、计数、页面三处共用同一口径。 */
    static LambdaQueryWrapper<WmsAuditLog> filter(WmsAuditQueryDTO q) {
        LambdaQueryWrapper<WmsAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (q == null) {
            return wrapper;
        }
        if (oConvertUtils.isNotEmpty(q.getBizType())) {
            wrapper.eq(WmsAuditLog::getBizType, q.getBizType());
        }
        if (oConvertUtils.isNotEmpty(q.getBizNo())) {
            wrapper.like(WmsAuditLog::getBizNo, q.getBizNo().trim());
        }
        if (oConvertUtils.isNotEmpty(q.getOperator())) {
            wrapper.eq(WmsAuditLog::getOperator, q.getOperator().trim());
        }
        if (oConvertUtils.isNotEmpty(q.getChannel())) {
            wrapper.eq(WmsAuditLog::getChannel, q.getChannel());
        }
        if (oConvertUtils.isNotEmpty(q.getResult())) {
            wrapper.eq(WmsAuditLog::getResult, q.getResult());
        }
        if (q.getStartTime() != null) {
            wrapper.ge(WmsAuditLog::getCreateTime, q.getStartTime());
        }
        if (q.getEndTime() != null) {
            wrapper.le(WmsAuditLog::getCreateTime, q.getEndTime());
        }
        return wrapper;
    }

    public long getExportedCount() {
        return exported;
    }
}
