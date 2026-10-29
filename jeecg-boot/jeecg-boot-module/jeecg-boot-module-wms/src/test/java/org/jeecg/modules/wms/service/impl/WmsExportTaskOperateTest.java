package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.exporter.WmsExportFileStore;
import org.jeecg.modules.wms.exporter.WmsExportTaskRunner;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出任务的下载 / 重试 / 删除（spec 2026-10-29 §5.4-§5.6）。
 */
class WmsExportTaskOperateTest {

    @TempDir
    Path tempDir;

    private WmsExportTaskMapper taskMapper;
    private WmsExportTaskRunner runner;
    private TaskExecutor executor;
    private WmsExportFileStore fileStore;
    private WmsExportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        runner = mock(WmsExportTaskRunner.class);
        executor = mock(TaskExecutor.class);
        fileStore = new WmsExportFileStore(tempDir);
        service = new WmsExportTaskServiceImpl(taskMapper, runner, executor,
                new WmsExportProperties(), fileStore);
    }

    private WmsExportTask task(String owner, String status) {
        WmsExportTask t = new WmsExportTask();
        t.setId("t1");
        t.setStatus(status);
        t.setCreateBy(owner);
        t.setTaskType(WmsConstants.EXPORT_TASK_TYPE_INVENTORY_LEDGER);
        t.setQueryParams("{\"v\":1,\"q\":{}}");
        return t;
    }

    private WmsExportTask successTaskWithFile(String owner) throws Exception {
        WmsExportTask t = task(owner, WmsConstants.EXPORT_STATUS_SUCCESS);
        t.setFileName("库存台账_20261029.xlsx");
        t.setFilePath("库存台账_20261029.xlsx");
        Files.writeString(tempDir.resolve(t.getFilePath()), "xlsx");
        return t;
    }

    @Test
    void otherUserCannotDownload() {
        when(taskMapper.selectById("t1")).thenReturn(task("lisi", WmsConstants.EXPORT_STATUS_SUCCESS));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveDownload("t1", "zhangsan"));
        assertEquals("无权访问该导出任务", ex.getMessage());
    }

    @Test
    void unknownTaskGivesSameMessageAsForeignTask() {
        when(taskMapper.selectById("none")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveDownload("none", "zhangsan"));
        assertEquals("无权访问该导出任务", ex.getMessage(), "不区分「不存在」和「别人的」，避免探测任务 ID");
    }

    @Test
    void downloadFailedTaskRejected() {
        when(taskMapper.selectById("t1")).thenReturn(task("zhangsan", WmsConstants.EXPORT_STATUS_FAILED));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveDownload("t1", "zhangsan"));
        assertEquals("任务未成功完成，无法下载", ex.getMessage());
    }

    @Test
    void downloadMissingFileGivesHumanMessage() {
        WmsExportTask t = task("zhangsan", WmsConstants.EXPORT_STATUS_SUCCESS);
        t.setFilePath("已经没了的文件.xlsx");
        when(taskMapper.selectById("t1")).thenReturn(t);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.resolveDownload("t1", "zhangsan"));
        assertEquals("导出文件已过期或已被清理，请重新导出", ex.getMessage());
    }

    @Test
    void downloadExistingFileReturnsIt() throws Exception {
        WmsExportTask t = successTaskWithFile("zhangsan");
        when(taskMapper.selectById("t1")).thenReturn(t);

        File file = service.resolveDownload("t1", "zhangsan");

        assertTrue(file.exists());
        assertEquals("库存台账_20261029.xlsx", file.getName());
    }

    @Test
    void retryOnlyFromFailed() {
        when(taskMapper.selectById("t1")).thenReturn(task("zhangsan", WmsConstants.EXPORT_STATUS_RUNNING));
        assertEquals("任务正在处理中，无法重试",
                assertThrows(RuntimeException.class, () -> service.retry("t1", "zhangsan")).getMessage());

        when(taskMapper.selectById("t1")).thenReturn(task("zhangsan", WmsConstants.EXPORT_STATUS_SUCCESS));
        assertEquals("任务已完成，请直接下载",
                assertThrows(RuntimeException.class, () -> service.retry("t1", "zhangsan")).getMessage());

        verify(executor, never()).execute(any());
    }

    @Test
    void retryResetsToPendingAndResubmits() {
        WmsExportTask failed = task("zhangsan", WmsConstants.EXPORT_STATUS_FAILED);
        failed.setErrorMsg("上次炸了");
        failed.setFileName("old.xlsx");
        failed.setFilePath("old.xlsx");
        failed.setFileSize(100L);
        failed.setRowCount(5L);
        failed.setFinishTime(new Date());
        failed.setExpireTime(new Date());
        when(taskMapper.selectById("t1")).thenReturn(failed);

        service.retry("t1", "zhangsan");

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        WmsExportTask updated = captor.getValue();
        assertEquals(WmsConstants.EXPORT_STATUS_PENDING, updated.getStatus());
        assertNull(updated.getErrorMsg());
        assertNull(updated.getFileName());
        assertNull(updated.getFilePath());
        assertNull(updated.getFileSize());
        assertNull(updated.getRowCount());
        assertNull(updated.getFinishTime());
        assertNull(updated.getExpireTime());

        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(runnable.capture());
        runnable.getValue().run();
        verify(runner).execute("t1", "zhangsan");
    }

    @Test
    void deleteRejectedWhileRunning() {
        when(taskMapper.selectById("t1")).thenReturn(task("zhangsan", WmsConstants.EXPORT_STATUS_RUNNING));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.deleteTask("t1", "zhangsan"));
        assertEquals("任务处理中，无法删除", ex.getMessage());
        verify(taskMapper, never()).deleteById(any(String.class));
    }

    @Test
    void deleteRemovesRecordAndFile() throws Exception {
        WmsExportTask t = successTaskWithFile("zhangsan");
        when(taskMapper.selectById("t1")).thenReturn(t);

        service.deleteTask("t1", "zhangsan");

        verify(taskMapper).deleteById("t1");
        assertFalse(Files.exists(tempDir.resolve("库存台账_20261029.xlsx")), "删任务要连文件一起删");
    }

    @Test
    void deleteIsIdempotentAboutMissingFile() {
        WmsExportTask t = task("zhangsan", WmsConstants.EXPORT_STATUS_SUCCESS);
        t.setFilePath("没了.xlsx");
        when(taskMapper.selectById("t1")).thenReturn(t);

        service.deleteTask("t1", "zhangsan");

        verify(taskMapper).deleteById("t1");
    }

    @Test
    void otherUserCannotRetryOrDelete() {
        when(taskMapper.selectById("t1")).thenReturn(task("lisi", WmsConstants.EXPORT_STATUS_FAILED));
        assertEquals("无权访问该导出任务",
                assertThrows(RuntimeException.class, () -> service.retry("t1", "zhangsan")).getMessage());
        assertEquals("无权访问该导出任务",
                assertThrows(RuntimeException.class, () -> service.deleteTask("t1", "zhangsan")).getMessage());
    }
}
