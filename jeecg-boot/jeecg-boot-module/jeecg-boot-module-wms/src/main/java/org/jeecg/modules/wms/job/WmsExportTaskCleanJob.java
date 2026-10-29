package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.exporter.WmsExportFileStore;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 过期导出任务清理（KC-004）。
 *
 * <p>按 {@code expire_time < now} 分批删：先删文件再删记录，文件已经不在了也算清理成功（幂等）。
 * 条件是 {@code expire_time}，所以 **SUCCESS 和 FAILED 一起被清**——失败任务同样会过期，
 * 不能让它无限堆积。
 *
 * <p>与其它 WMS 定时任务同构：由 Registrar 注册、关闭时主动摘除；执行体吞异常，清理失败不影响应用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
@DisallowConcurrentExecution
public class WmsExportTaskCleanJob implements Job {

    @Autowired
    private WmsExportTaskMapper taskMapper;

    @Autowired
    private WmsExportFileStore fileStore;

    /** Quartz 需要可访问的无参构造器。 */
    public WmsExportTaskCleanJob() {
    }

    /** 仅供单测注入依赖。 */
    WmsExportTaskCleanJob(WmsExportTaskMapper taskMapper, WmsExportFileStore fileStore) {
        this.taskMapper = taskMapper;
        this.fileStore = fileStore;
    }

    @Override
    public void execute(JobExecutionContext context) {
        clean();
    }

    /**
     * 分批清理过期任务，直到没有更多过期记录。
     *
     * @return 清理掉的记录数
     */
    int clean() {
        int total = 0;
        try {
            while (true) {
                List<WmsExportTask> batch = taskMapper.selectList(new LambdaQueryWrapper<WmsExportTask>()
                        .isNotNull(WmsExportTask::getExpireTime)
                        .lt(WmsExportTask::getExpireTime, new Date())
                        // 双保险：正在处理的任务即便挂着陈旧的 expire_time 也绝不能被清理
                        .notIn(WmsExportTask::getStatus,
                                Arrays.asList(WmsConstants.EXPORT_STATUS_PENDING, WmsConstants.EXPORT_STATUS_RUNNING))
                        .last("LIMIT " + WmsConstants.EXPORT_TASK_CLEAN_BATCH_SIZE));
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (WmsExportTask task : batch) {
                    fileStore.deleteQuietly(fileStore.resolve(task.getFilePath()));
                }
                List<String> ids = batch.stream().map(WmsExportTask::getId).collect(Collectors.toList());
                taskMapper.deleteBatchIds(ids);
                total += batch.size();
                if (batch.size() < WmsConstants.EXPORT_TASK_CLEAN_BATCH_SIZE) {
                    break;
                }
            }
            if (total > 0) {
                log.info("导出任务清理完成，删除 {} 条过期任务", total);
            }
        } catch (Exception e) {
            // 清理失败不能影响应用和其它任务
            log.error("导出任务清理失败：{}", e.getMessage(), e);
        }
        return total;
    }
}
