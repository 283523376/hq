package org.jeecg.modules.wms.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.exporter.WmsExportTaskRunner;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

import java.util.Date;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出任务创建：单用户并发、队列拒绝收尾、条件快照（spec 2026-10-29 §5.2 / §6.1）。
 */
class WmsExportTaskCreateTest {

    /** LambdaQueryWrapper 解析列名要靠 TableInfo，纯单测环境里手动注册一次。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WmsExportTask.class);
    }

    private WmsExportTaskMapper taskMapper;
    private WmsExportTaskRunner runner;
    private TaskExecutor executor;
    private WmsExportTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        runner = mock(WmsExportTaskRunner.class);
        executor = mock(TaskExecutor.class);
        service = new WmsExportTaskServiceImpl(taskMapper, runner, executor, new WmsExportProperties());
    }

    @Test
    void createPersistsPendingTaskWithOperator() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        String taskId = service.createLedgerExport(new WmsInventoryLedgerQueryDTO(), "zhangsan");

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).insert(captor.capture());
        WmsExportTask saved = captor.getValue();
        assertNotNull(taskId);
        assertEquals(taskId, saved.getId());
        assertEquals(WmsConstants.EXPORT_STATUS_PENDING, saved.getStatus());
        assertEquals(WmsConstants.EXPORT_TASK_TYPE_INVENTORY_LEDGER, saved.getTaskType());
        assertEquals("zhangsan", saved.getCreateBy());
        assertNotNull(saved.getCreateTime());
        assertNull(saved.getExpireTime(), "排队中的任务还没有终态，不应有清理时点");
    }

    @Test
    void rejectsWhenSameUserHasRunningTask() {
        when(taskMapper.selectCount(any())).thenReturn(1L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createLedgerExport(new WmsInventoryLedgerQueryDTO(), "zhangsan"));

        assertEquals("你已有导出任务正在进行，请等待完成后再提交", ex.getMessage());
        verify(taskMapper, never()).insert(any(WmsExportTask.class));
        verify(executor, never()).execute(any());
    }

    @Test
    void rejectedByPoolMarksTaskFailed() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createLedgerExport(new WmsInventoryLedgerQueryDTO(), "zhangsan"));
        assertEquals("导出任务较多，请稍后再试", ex.getMessage());

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        WmsExportTask updated = captor.getValue();
        assertEquals(WmsConstants.EXPORT_STATUS_FAILED, updated.getStatus());
        assertEquals("导出任务队列已满，请稍后重试", updated.getErrorMsg());
        assertNotNull(updated.getFinishTime());
        assertNotNull(updated.getExpireTime(), "失败任务也必须写清理时点，否则永远不被清理");
        assertTrue(updated.getExpireTime().after(updated.getFinishTime()));
    }

    @Test
    void taskListUsesOnlyTheGivenOperator() {
        when(taskMapper.selectPage(any(), any())).thenReturn(new Page<>());
        service.pageMine("zhangsan", 1, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsExportTask>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(taskMapper).selectPage(any(), captor.capture());
        String segment = captor.getValue().getSqlSegment();
        java.util.Map<String, Object> params =
                ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) captor.getValue())
                        .getParamNameValuePairs();
        assertTrue(segment.contains("create_by"), "必须按 create_by 过滤，实际: " + segment);
        assertTrue(params.containsValue("zhangsan"), "查询条件里必须只有传入的账号");
        assertEquals(1, params.size(), "除了账号不应再有别的绑定参数（状态过滤用常量拼接，不走参数）");
    }

    @Test
    void queryParamsSerializedWithVersion() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setMaterialKeyword("M007");
        q.setBatchNo("B1");
        service.createLedgerExport(q, "zhangsan");

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).insert(captor.capture());
        JSONObject json = JSON.parseObject(captor.getValue().getQueryParams());
        assertEquals(1, json.getIntValue("v"));
        JSONObject cond = json.getJSONObject("q");
        assertEquals("M007", cond.getString("materialKeyword"));
        assertEquals("B1", cond.getString("batchNo"));
    }

    @Test
    void submittedTaskCarriesTaskIdAndOperator() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        String taskId = service.createLedgerExport(new WmsInventoryLedgerQueryDTO(), "zhangsan");

        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(runnable.capture());
        runnable.getValue().run();
        verify(runner).execute(taskId, "zhangsan");
    }

    @Test
    void expireTimeIsRetentionDaysAfterFinish() {
        when(taskMapper.selectCount(any())).thenReturn(0L);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        assertThrows(RuntimeException.class,
                () -> service.createLedgerExport(new WmsInventoryLedgerQueryDTO(), "zhangsan"));

        ArgumentCaptor<WmsExportTask> captor = ArgumentCaptor.forClass(WmsExportTask.class);
        verify(taskMapper).updateById(captor.capture());
        Date finish = captor.getValue().getFinishTime();
        Date expire = captor.getValue().getExpireTime();
        long days = (expire.getTime() - finish.getTime()) / (24L * 60 * 60 * 1000);
        assertEquals(WmsConstants.EXPORT_RETENTION_DAYS, days);
    }
}
