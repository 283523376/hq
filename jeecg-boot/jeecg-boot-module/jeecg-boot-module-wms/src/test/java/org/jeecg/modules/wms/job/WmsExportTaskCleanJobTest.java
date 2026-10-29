package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.exporter.WmsExportFileStore;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 过期导出任务的清理（spec 2026-10-29 §4.4）。
 */
class WmsExportTaskCleanJobTest {

    @TempDir
    Path tempDir;

    private WmsExportTaskMapper taskMapper;
    private WmsExportFileStore fileStore;
    private WmsExportTaskCleanJob job;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WmsExportTask.class);
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        fileStore = new WmsExportFileStore(tempDir);
        job = new WmsExportTaskCleanJob(taskMapper, fileStore);
    }

    private WmsExportTask task(String id, String fileName) {
        WmsExportTask t = new WmsExportTask();
        t.setId(id);
        t.setStatus(WmsConstants.EXPORT_STATUS_SUCCESS);
        t.setFilePath(fileName);
        t.setExpireTime(new Date(System.currentTimeMillis() - 86_400_000L));
        return t;
    }

    @Test
    void deletesExpiredTaskAndItsFile() throws Exception {
        Files.writeString(tempDir.resolve("old.xlsx"), "x");
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t1", "old.xlsx")), Collections.emptyList());

        job.clean();

        ArgumentCaptor<Wrapper<WmsExportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        @SuppressWarnings("unchecked")
        String segment = ((AbstractWrapper<?, ?, ?>) captor.getValue()).getSqlSegment();
        assertTrue(segment.contains("expire_time"), "清理条件必须按 expire_time，实际: " + segment);

        verify(taskMapper).deleteBatchIds(any());
        assertFalse(Files.exists(tempDir.resolve("old.xlsx")), "记录删了文件也要删");
    }

    @Test
    void missingFileDoesNotStopCleanup() {
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(task("t1", "已经没了.xlsx")), Collections.emptyList());

        job.clean();

        verify(taskMapper).deleteBatchIds(any());
    }

    @Test
    void nothingToCleanMeansNoDelete() {
        when(taskMapper.selectList(any())).thenReturn(Collections.emptyList());

        int cleaned = job.clean();

        assertTrue(cleaned == 0);
        verify(taskMapper, never()).deleteBatchIds(any());
    }

    @Test
    void cleansFailedTasksToo() throws Exception {
        Files.writeString(tempDir.resolve("failed.xlsx"), "x");
        WmsExportTask failed = task("t2", "failed.xlsx");
        failed.setStatus(WmsConstants.EXPORT_STATUS_FAILED);
        when(taskMapper.selectList(any())).thenReturn(Arrays.asList(failed), Collections.emptyList());

        job.clean();

        verify(taskMapper).deleteBatchIds(any());
        assertFalse(Files.exists(tempDir.resolve("failed.xlsx")), "失败任务同样 7 天后清理");
    }
}
