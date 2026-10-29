package org.jeecg.modules.wms.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.exporter.WmsExportExecutorConfig;
import org.jeecg.modules.wms.exporter.WmsExportFileStore;
import org.jeecg.modules.wms.exporter.WmsExportTaskRunner;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.IWmsExportTaskService;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.RejectedExecutionException;

/**
 * @Description: 导出任务（KC-004）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Service
public class WmsExportTaskServiceImpl implements IWmsExportTaskService {

    /** 查询条件快照的格式版本：将来条件结构变了，靠它识别旧任务。 */
    private static final int QUERY_PARAMS_VERSION = 1;

    private final WmsExportTaskMapper taskMapper;
    private final WmsExportTaskRunner taskRunner;
    private final TaskExecutor exportExecutor;
    private final WmsExportProperties properties;
    private final WmsExportFileStore fileStore;

    /**
     * 按账号的临界区锁：把「查有没有进行中任务」和「插入新任务」这两步串起来。
     *
     * <p>不加锁时两个并发请求可能在同一个窗口里都查到 0 条，各建一条 PENDING（TOCTOU）。
     * 单实例部署用进程内锁就够；将来上多实例，这层要换成数据库约束或分布式锁。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> operatorLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public WmsExportTaskServiceImpl(WmsExportTaskMapper taskMapper,
                                    WmsExportTaskRunner taskRunner,
                                    @Qualifier(WmsExportExecutorConfig.EXPORT_EXECUTOR) TaskExecutor exportExecutor,
                                    WmsExportProperties properties,
                                    WmsExportFileStore fileStore) {
        this.taskMapper = taskMapper;
        this.taskRunner = taskRunner;
        this.exportExecutor = exportExecutor;
        this.properties = properties;
        this.fileStore = fileStore;
    }

    @Override
    public String createLedgerExport(WmsInventoryLedgerQueryDTO query, String operator) {
        Object lock = operatorLocks.computeIfAbsent(operator, k -> new Object());
        synchronized (lock) {
            return doCreateLedgerExport(query, operator);
        }
    }

    private String doCreateLedgerExport(WmsInventoryLedgerQueryDTO query, String operator) {
        long running = taskMapper.selectCount(new LambdaQueryWrapper<WmsExportTask>()
                .eq(WmsExportTask::getCreateBy, operator)
                .in(WmsExportTask::getStatus,
                        Arrays.asList(WmsConstants.EXPORT_STATUS_PENDING, WmsConstants.EXPORT_STATUS_RUNNING)));
        if (running > 0) {
            throw new RuntimeException("你已有导出任务正在进行，请等待完成后再提交");
        }

        Date now = new Date();
        // 注意：JeecgEntity 的 setId 返回父类型，链式调用到这里会断，所以拆开赋值
        WmsExportTask task = new WmsExportTask();
        task.setId(IdWorker.getIdStr());
        task.setTaskType(WmsConstants.EXPORT_TASK_TYPE_INVENTORY_LEDGER);
        task.setQueryParams(snapshotQuery(query));
        task.setStatus(WmsConstants.EXPORT_STATUS_PENDING);
        task.setCreateBy(operator);
        task.setCreateTime(now);
        task.setUpdateBy(operator);
        task.setUpdateTime(now);
        taskMapper.insert(task);

        submit(task, operator);
        log.info("导出任务[{}]已提交，导出人={}", task.getId(), operator);
        return task.getId();
    }

    @Override
    public IPage<WmsExportTask> pageMine(String operator, int pageNo, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), WmsConstants.EXPORT_TASK_MAX_PAGE_SIZE);
        return taskMapper.selectPage(new Page<>(pageNo == 0 ? 1 : pageNo, size),
                new LambdaQueryWrapper<WmsExportTask>()
                        .eq(WmsExportTask::getCreateBy, operator)
                        .orderByDesc(WmsExportTask::getCreateTime));
    }

    @Override
    public File resolveDownload(String taskId, String operator) {
        WmsExportTask task = requireOwned(taskId, operator);
        if (!WmsConstants.EXPORT_STATUS_SUCCESS.equals(task.getStatus())) {
            throw new RuntimeException("任务未成功完成，无法下载");
        }
        File file = fileStore.resolve(task.getFilePath());
        if (file == null || !file.exists()) {
            throw new RuntimeException("导出文件已过期或已被清理，请重新导出");
        }
        return file;
    }

    @Override
    public void retry(String taskId, String operator) {
        WmsExportTask task = requireOwned(taskId, operator);
        if (WmsConstants.EXPORT_STATUS_PENDING.equals(task.getStatus())
                || WmsConstants.EXPORT_STATUS_RUNNING.equals(task.getStatus())) {
            throw new RuntimeException("任务正在处理中，无法重试");
        }
        if (!WmsConstants.EXPORT_STATUS_FAILED.equals(task.getStatus())) {
            throw new RuntimeException("任务已完成，请直接下载");
        }

        // 清掉上一次的产出，避免重试后的任务还挂着旧文件信息。
        //
        // **必须用显式 .set(...) 的 LambdaUpdateWrapper，不能用 updateById**：
        // MyBatis-Plus 默认更新策略是 NOT_NULL，实体里 set 成 null 的字段会被整段跳过，
        // 旧的 error_msg / finish_time / expire_time 会原样留在库里。后果不只是列表脏：
        // 残留的旧 expire_time 会让清理 Job 按 expire_time < now 把**正在跑的任务**当过期记录删掉。
        Date now = new Date();
        taskMapper.update(null, new LambdaUpdateWrapper<WmsExportTask>()
                .eq(WmsExportTask::getId, taskId)
                .set(WmsExportTask::getStatus, WmsConstants.EXPORT_STATUS_PENDING)
                .set(WmsExportTask::getErrorMsg, null)
                .set(WmsExportTask::getFileName, null)
                .set(WmsExportTask::getFilePath, null)
                .set(WmsExportTask::getFileSize, null)
                .set(WmsExportTask::getRowCount, null)
                .set(WmsExportTask::getSnapshotTime, null)
                .set(WmsExportTask::getFinishTime, null)
                .set(WmsExportTask::getExpireTime, null)
                .set(WmsExportTask::getUpdateBy, task.getCreateBy())
                .set(WmsExportTask::getUpdateTime, now));

        // 内存态同步，供后续 submit/markFailed 使用
        task.setStatus(WmsConstants.EXPORT_STATUS_PENDING);
        task.setErrorMsg(null);
        task.setFileName(null);
        task.setFilePath(null);
        task.setFileSize(null);
        task.setRowCount(null);
        task.setSnapshotTime(null);
        task.setFinishTime(null);
        task.setExpireTime(null);
        task.setUpdateBy(task.getCreateBy());
        task.setUpdateTime(now);

        submit(task, operator);
        log.info("导出任务[{}]已重试，导出人={}", taskId, operator);
    }

    @Override
    public void deleteTask(String taskId, String operator) {
        WmsExportTask task = requireOwned(taskId, operator);
        if (WmsConstants.EXPORT_STATUS_PENDING.equals(task.getStatus())
                || WmsConstants.EXPORT_STATUS_RUNNING.equals(task.getStatus())) {
            throw new RuntimeException("任务处理中，无法删除");
        }
        fileStore.deleteQuietly(fileStore.resolve(task.getFilePath()));
        taskMapper.deleteById(taskId);
        log.info("导出任务[{}]已删除，导出人={}", taskId, operator);
    }

    /**
     * 取别人的任务和取不存在的任务给同一个文案——不给探测他人任务 ID 留缝隙。
     */
    private WmsExportTask requireOwned(String taskId, String operator) {
        WmsExportTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null || !operator.equals(task.getCreateBy())) {
            throw new RuntimeException("无权访问该导出任务");
        }
        return task;
    }

    /**
     * 丢进导出线程池；队列满时把已落库的行收尾成 FAILED 再把异常抛给调用方。
     *
     * <p>不能只抛异常不管那一行——它会永远停在 PENDING，前端按「存在 PENDING 就轮询」会一直转。
     */
    private void submit(WmsExportTask task, String operator) {
        String taskId = task.getId();
        try {
            exportExecutor.execute(() -> taskRunner.execute(taskId, operator));
        } catch (RejectedExecutionException e) {
            log.warn("导出任务[{}]提交线程池被拒，标记为失败", taskId);
            markFailed(task, "导出任务队列已满，请稍后重试");
            throw new RuntimeException("导出任务较多，请稍后再试");
        }
    }

    /** 把查询条件序列化成带版本号的 JSON 快照。 */
    private String snapshotQuery(WmsInventoryLedgerQueryDTO query) {
        JSONObject root = new JSONObject();
        root.put("v", QUERY_PARAMS_VERSION);
        root.put("q", query == null ? new WmsInventoryLedgerQueryDTO() : query);
        return root.toJSONString();
    }

    /** 把任务写成终态 FAILED，并补上清理时点（任何终态都要有 expire_time）。 */
    private void markFailed(WmsExportTask task, String errorMsg) {
        Date finish = new Date();
        task.setStatus(WmsConstants.EXPORT_STATUS_FAILED);
        task.setErrorMsg(errorMsg);
        task.setFinishTime(finish);
        task.setExpireTime(expireAfter(finish));
        task.setUpdateBy(task.getCreateBy());
        task.setUpdateTime(finish);
        taskMapper.updateById(task);
    }

    private Date expireAfter(Date finish) {
        return new Date(finish.getTime() + properties.getRetentionDays() * 24L * 60 * 60 * 1000);
    }
}
