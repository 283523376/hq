package org.jeecg.modules.wms.exporter;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重启后的孤儿任务收尾（spec 2026-10-29 §6.4）。
 */
class WmsExportTaskRecoveryTest {

    @TempDir
    java.nio.file.Path tempDir;

    private WmsExportTaskMapper taskMapper;
    private WmsExportFileStore fileStore;
    private WmsExportTaskRecovery recovery;

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        fileStore = new WmsExportFileStore(tempDir);
        recovery = new WmsExportTaskRecovery(taskMapper, new WmsExportProperties(), fileStore);
    }

    private WmsExportTask task(String id, String status) {
        WmsExportTask t = new WmsExportTask();
        t.setId(id);
        t.setStatus(status);
        t.setCreateBy("lisi");
        return t;
    }

    @Test
    void runningBecomesFailed() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t1", WmsConstants.EXPORT_STATUS_RUNNING)));

        recovery.recover();

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(WmsConstants.EXPORT_STATUS_FAILED, captor.getValue().getStatus());
        assertEquals("服务重启，任务中断，请重试", captor.getValue().getErrorMsg());
    }

    @Test
    void pendingBecomesFailed() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t2", WmsConstants.EXPORT_STATUS_PENDING)));

        recovery.recover();

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        assertEquals(WmsConstants.EXPORT_STATUS_FAILED, captor.getValue().getStatus());
    }

    @Test
    void recoverySetsFinishAndExpireTime() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t1", WmsConstants.EXPORT_STATUS_RUNNING)));

        recovery.recover();

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        Date finish = captor.getValue().getFinishTime();
        Date expire = captor.getValue().getExpireTime();
        assertNotNull(finish);
        assertNotNull(expire, "收尾成 FAILED 时也必须写清理时点");
        long days = (expire.getTime() - finish.getTime()) / (24L * 60 * 60 * 1000);
        assertEquals(WmsConstants.EXPORT_RETENTION_DAYS, days);
        assertEquals("lisi", captor.getValue().getUpdateBy(), "后台线程没有登录人，只能用任务自己的导出人");
    }

    @Test
    void noTasksMeansNoUpdate() {
        when(taskMapper.selectList(any())).thenReturn(Collections.emptyList());

        int recovered = recovery.recover();

        assertEquals(0, recovered);
        verify(taskMapper, never()).updateById(any(WmsExportTask.class));
    }

    @Test
    void removesOrphanPartFile() throws Exception {
        // 进程崩溃留下的 <taskId>.part 不在任何清理路径上：任务行 file_path 为 null，清理 Job 摸不到它
        java.io.File part = fileStore.partFile("t1");
        java.nio.file.Files.writeString(part.toPath(), "half-written");
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t1", WmsConstants.EXPORT_STATUS_RUNNING)));

        recovery.recover();

        assertFalse(java.nio.file.Files.exists(part.toPath()), "恢复时必须顺手删掉孤儿 .part");
    }

    @Test
    void recoveredCountIsReturned() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(
                task("t1", WmsConstants.EXPORT_STATUS_RUNNING),
                task("t2", WmsConstants.EXPORT_STATUS_PENDING)));

        assertTrue(recovery.recover() == 2);
    }
}
