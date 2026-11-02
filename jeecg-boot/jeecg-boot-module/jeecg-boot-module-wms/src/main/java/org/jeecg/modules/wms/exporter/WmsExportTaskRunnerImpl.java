package org.jeecg.modules.wms.exporter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.jeecg.modules.wms.service.impl.WmsInventoryLedgerServiceImpl;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Date;

/**
 * @Description: 导出任务执行器（KC-004）。
 *
 * <p>跑在导出线程池里，**没有 Shiro Subject**：{@code create_by}/{@code update_by} 一律用任务自己的
 * 导出人，绝不调用 {@code SecurityUtils.getSubject()}。
 *
 * <p>状态流转 PENDING → RUNNING → SUCCESS/FAILED，任何终态都写 {@code expire_time}，
 * 否则失败任务会永远不过期、永远不被清理。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Service
public class WmsExportTaskRunnerImpl implements WmsExportTaskRunner {

    /** 查询条件快照的格式版本，与 {@code WmsExportTaskServiceImpl} 写入时保持一致。 */
    private static final int QUERY_PARAMS_VERSION = 1;

    private final WmsExportTaskMapper taskMapper;
    private final WmsLedgerExportService exportService;
    private final WmsAuditExportService auditExportService;
    private final WmsExportFileStore fileStore;
    private final WmsExportProperties properties;

    public WmsExportTaskRunnerImpl(WmsExportTaskMapper taskMapper,
                                   WmsLedgerExportService exportService,
                                   WmsAuditExportService auditExportService,
                                   WmsExportFileStore fileStore,
                                   WmsExportProperties properties) {
        this.taskMapper = taskMapper;
        this.exportService = exportService;
        this.auditExportService = auditExportService;
        this.fileStore = fileStore;
        this.properties = properties;
    }

    @Override
    public void execute(String taskId, String operator) {
        WmsExportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("导出任务[{}]不存在，跳过（可能已被删除）", taskId);
            return;
        }
        markRunning(task);

        File part = fileStore.partFile(taskId);
        try {
            WmsLedgerExportResult result = generateByTaskType(task, part);

            String prefix = WmsConstants.EXPORT_TASK_TYPE_AUDIT_LOG.equals(task.getTaskType())
                    ? "审计日志_" : "库存台账_";
            String fileName = fileStore.buildFileName(prefix, taskId, new Date());
            File done = fileStore.commit(part, fileName);
            markSuccess(task, fileName, done, result);
        } catch (Exception e) {
            fileStore.deleteQuietly(part);
            log.error("导出任务[{}]失败", taskId, e);
            markFailed(task, e);
        }
    }

    /**
     * 按 taskType 分发到对应的导出器。
     *
     * <p>未知/空类型按库存台账处理——历史上所有任务都是台账，不能让老任务失败。
     */
    private WmsLedgerExportResult generateByTaskType(WmsExportTask task, File target) throws Exception {
        if (WmsConstants.EXPORT_TASK_TYPE_AUDIT_LOG.equals(task.getTaskType())) {
            return auditExportService.generate(parseAuditQuery(task.getQueryParams()), target);
        }
        if (task.getTaskType() != null
                && !WmsConstants.EXPORT_TASK_TYPE_INVENTORY_LEDGER.equals(task.getTaskType())) {
            log.warn("导出任务[{}]的类型 {} 未知，按库存台账处理", task.getId(), task.getTaskType());
        }
        WmsInventoryLedgerQueryDTO query = parseQuery(task.getQueryParams());
        return exportService.generate(query, target);
    }

    /** 审计导出条件快照（结构与台账一致：{v, q}）。 */
    WmsAuditQueryDTO parseAuditQuery(String queryParams) {
        if (queryParams == null || queryParams.trim().isEmpty()) {
            return new WmsAuditQueryDTO();
        }
        try {
            JSONObject root = JSON.parseObject(queryParams);
            if (root == null) {
                return new WmsAuditQueryDTO();
            }
            WmsAuditQueryDTO query = root.getObject("q", WmsAuditQueryDTO.class);
            return query == null ? new WmsAuditQueryDTO() : query;
        } catch (Exception e) {
            log.warn("审计导出条件快照解析失败，按无条件导出处理：{}", e.getMessage());
            return new WmsAuditQueryDTO();
        }
    }

    /**
     * 反序列化查询条件快照。
     *
     * <p>快照损坏时不阻断任务——退化成「无条件全量导出」比直接失败更有用，
     * 但要过一遍归一化，保证和老任务的条件口径一致。
     */
    WmsInventoryLedgerQueryDTO parseQuery(String queryParams) {
        WmsInventoryLedgerQueryDTO query = null;
        if (queryParams != null && !queryParams.trim().isEmpty()) {
            try {
                JSONObject root = JSON.parseObject(queryParams);
                if (root != null) {
                    Integer version = root.getInteger("v");
                    if (version == null || version != QUERY_PARAMS_VERSION) {
                        // 版本号不是装饰：将来快照结构变了，靠它识别旧任务并按兼容方式读。
                        // 当前只有 v=1，未知版本先按现有结构尽力解析，不整条任务失败。
                        log.warn("导出条件快照版本为 {}（当前支持 {}），按现有结构解析",
                                version, QUERY_PARAMS_VERSION);
                    }
                    query = root.getObject("q", WmsInventoryLedgerQueryDTO.class);
                }
            } catch (Exception e) {
                log.warn("导出条件快照解析失败，按无条件导出处理：{}", e.getMessage());
            }
        }
        return WmsInventoryLedgerServiceImpl.normalize(query);
    }

    private void markRunning(WmsExportTask task) {
        task.setStatus(WmsConstants.EXPORT_STATUS_RUNNING);
        task.setUpdateBy(task.getCreateBy());
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
    }

    private void markSuccess(WmsExportTask task, String fileName, File done, WmsLedgerExportResult result) {
        Date finish = new Date();
        task.setStatus(WmsConstants.EXPORT_STATUS_SUCCESS);
        task.setFileName(fileName);
        task.setFilePath(fileName);
        task.setFileSize(done.length());
        task.setRowCount(result.rowCount());
        task.setSnapshotTime(result.snapshotTime());
        task.setFinishTime(finish);
        task.setExpireTime(expireAfter(finish));
        task.setErrorMsg(null);
        task.setUpdateBy(task.getCreateBy());
        task.setUpdateTime(finish);
        taskMapper.updateById(task);
        log.info("导出任务[{}]完成，{} 行，{} 字节", task.getId(), result.rowCount(), done.length());
    }

    private void markFailed(WmsExportTask task, Exception e) {
        Date finish = new Date();
        task.setStatus(WmsConstants.EXPORT_STATUS_FAILED);
        task.setErrorMsg(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        task.setFinishTime(finish);
        task.setExpireTime(expireAfter(finish));
        task.setUpdateBy(task.getCreateBy());
        task.setUpdateTime(finish);
        taskMapper.updateById(task);
    }

    private String truncate(String message) {
        return message.length() <= WmsConstants.EXPORT_ERROR_MSG_MAX_LEN
                ? message : message.substring(0, WmsConstants.EXPORT_ERROR_MSG_MAX_LEN);
    }

    private Date expireAfter(Date finish) {
        return new Date(finish.getTime() + properties.getRetentionDays() * 24L * 60 * 60 * 1000);
    }
}
