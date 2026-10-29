package org.jeecg.modules.wms.exporter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * @Description: 重启后的孤儿导出任务收尾（KC-004）。
 *
 * <p>线程池里的任务和队列随进程一起没了，但库里的行还停在 PENDING/RUNNING。
 * 不收拾的话：前端会一直轮询，而且这个用户再也提交不了新任务（单用户并发限制会把它当「进行中」）。
 *
 * <p>**不做断点续传**：几十万行重跑一次几分钟，续传的复杂度不值得。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Slf4j
@Component
public class WmsExportTaskRecovery {

    private final WmsExportTaskMapper taskMapper;
    private final WmsExportProperties properties;

    public WmsExportTaskRecovery(WmsExportTaskMapper taskMapper, WmsExportProperties properties) {
        this.taskMapper = taskMapper;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recover();
    }

    /**
     * 把 PENDING/RUNNING 一律置为 FAILED，并补上完成时间与清理时点。
     *
     * @return 收尾的任务数
     */
    int recover() {
        try {
            List<WmsExportTask> orphans = taskMapper.selectList(new LambdaQueryWrapper<WmsExportTask>()
                    .in(WmsExportTask::getStatus,
                            Arrays.asList(WmsConstants.EXPORT_STATUS_PENDING, WmsConstants.EXPORT_STATUS_RUNNING)));
            if (orphans == null || orphans.isEmpty()) {
                return 0;
            }
            Date now = new Date();
            Date expire = new Date(now.getTime() + properties.getRetentionDays() * 24L * 60 * 60 * 1000);
            for (WmsExportTask task : orphans) {
                task.setStatus(WmsConstants.EXPORT_STATUS_FAILED);
                task.setErrorMsg("服务重启，任务中断，请重试");
                task.setFinishTime(now);
                task.setExpireTime(expire);
                task.setUpdateBy(task.getCreateBy());
                task.setUpdateTime(now);
                taskMapper.updateById(task);
            }
            log.info("导出任务启动恢复：{} 条中断任务已置为失败", orphans.size());
            return orphans.size();
        } catch (Exception e) {
            log.error("导出任务启动恢复失败：{}", e.getMessage(), e);
            return 0;
        }
    }
}
