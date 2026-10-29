package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出执行器的状态流转与文件收尾（spec 2026-10-29 §6.2 / §6.4）。
 */
class WmsExportTaskRunnerTest {

    @TempDir
    Path tempDir;

    private WmsExportTaskMapper taskMapper;
    private WmsLedgerExportService exportService;
    private WmsExportFileStore fileStore;
    private WmsExportTaskRunnerImpl runner;

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        exportService = mock(WmsLedgerExportService.class);
        fileStore = new WmsExportFileStore(tempDir);
        runner = new WmsExportTaskRunnerImpl(taskMapper, exportService, fileStore, new WmsExportProperties());
    }

    private WmsExportTask pendingTask(String queryParams) {
        WmsExportTask task = new WmsExportTask();
        task.setId("t1");
        task.setTaskType(WmsConstants.EXPORT_TASK_TYPE_INVENTORY_LEDGER);
        task.setQueryParams(queryParams);
        task.setStatus(WmsConstants.EXPORT_STATUS_PENDING);
        task.setCreateBy("lisi");
        task.setCreateTime(new Date());
        return task;
    }

    private List<String> filesInTempDir() throws Exception {
        try (Stream<Path> s = Files.list(tempDir)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        }
    }

    @Test
    void successPathWritesFileMetaAndExpireTime() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        doAnswer(inv -> {
            File target = inv.getArgument(1);
            Files.writeString(target.toPath(), "xlsx-bytes");
            return new WmsLedgerExportResult(31L, new Date());
        }).when(exportService).generate(any(), any());

        runner.execute("t1", "lisi");

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper, atLeastOnce()).updateById(captor.capture());
        WmsExportTask done = captor.getValue();
        assertEquals(WmsConstants.EXPORT_STATUS_SUCCESS, done.getStatus());
        assertEquals(31L, done.getRowCount());
        assertEquals(10L, done.getFileSize());
        assertNotNull(done.getFileName());
        assertNotNull(done.getFilePath());
        assertNotNull(done.getFinishTime());
        assertNotNull(done.getExpireTime());
        assertNotNull(done.getSnapshotTime());
        assertTrue(Files.exists(tempDir.resolve(done.getFilePath())), ".part 必须已改名成最终文件");
        assertEquals(1, filesInTempDir().size(), "目录里不该留下 .part");
    }

    @Test
    void failurePathWritesFailedStatusAndTruncatedError() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        String longMessage = "x".repeat(1500);
        when(exportService.generate(any(), any())).thenThrow(new RuntimeException(longMessage));

        runner.execute("t1", "lisi");

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper, atLeastOnce()).updateById(captor.capture());
        WmsExportTask done = captor.getValue();
        assertEquals(WmsConstants.EXPORT_STATUS_FAILED, done.getStatus());
        assertEquals(WmsConstants.EXPORT_ERROR_MSG_MAX_LEN, done.getErrorMsg().length());
        assertNotNull(done.getFinishTime());
        assertNotNull(done.getExpireTime(), "失败任务也必须写清理时点，否则永远不被清理");
        assertNull(done.getFilePath());
    }

    @Test
    void tempFileRemovedAndNoFinalFileOnFailure() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        doAnswer(inv -> {
            File target = inv.getArgument(1);
            Files.writeString(target.toPath(), "half-written");
            throw new RuntimeException("写盘炸了");
        }).when(exportService).generate(any(), any());

        runner.execute("t1", "lisi");

        assertEquals(0, filesInTempDir().size(), "失败时必须删掉半个文件，目录应清空");
    }

    @Test
    void updateByUsesTaskOwnerNotShiroSubject() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        doAnswer(inv -> {
            Files.writeString(((File) inv.getArgument(1)).toPath(), "x");
            return new WmsLedgerExportResult(1L, new Date());
        }).when(exportService).generate(any(), any());

        runner.execute("t1", "lisi");

        assertEquals("lisi", task.getUpdateBy(), "后台线程没有 Shiro Subject，update_by 只能用任务自己的导出人");
    }

    @Test
    void missingTaskIsSkippedSilently() throws Exception {
        when(taskMapper.selectById("gone")).thenReturn(null);

        runner.execute("gone", "lisi");

        verify(exportService, never()).generate(any(), any());
        verify(taskMapper, never()).updateById(any(WmsExportTask.class));
    }

    @Test
    void queryParamsDeserializedAndNormalized() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{\"materialKeyword\":\"  M007  \",\"pageSize\":9999}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        doAnswer(inv -> {
            Files.writeString(((File) inv.getArgument(1)).toPath(), "x");
            return new WmsLedgerExportResult(1L, new Date());
        }).when(exportService).generate(any(), any());

        runner.execute("t1", "lisi");

        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor = ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(exportService).generate(captor.capture(), any());
        assertEquals("M007", captor.getValue().getMaterialKeyword(), "反序列化回来的条件必须过一遍归一化");
    }

    @Test
    void brokenQueryParamsStillRunsWithEmptyConditions() throws Exception {
        WmsExportTask task = pendingTask("not-a-json");
        when(taskMapper.selectById("t1")).thenReturn(task);
        doAnswer(inv -> {
            Files.writeString(((File) inv.getArgument(1)).toPath(), "x");
            return new WmsLedgerExportResult(0L, new Date());
        }).when(exportService).generate(any(), any());

        runner.execute("t1", "lisi");

        verify(exportService).generate(any(), any());
        assertEquals(WmsConstants.EXPORT_STATUS_SUCCESS, task.getStatus());
    }

    @Test
    void marksRunningBeforeGenerating() throws Exception {
        WmsExportTask task = pendingTask("{\"v\":1,\"q\":{}}");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(exportService.generate(any(), any())).thenThrow(new RuntimeException("boom"));

        runner.execute("t1", "lisi");

        assertFalse(WmsConstants.EXPORT_STATUS_PENDING.equals(task.getStatus()));
    }
}
