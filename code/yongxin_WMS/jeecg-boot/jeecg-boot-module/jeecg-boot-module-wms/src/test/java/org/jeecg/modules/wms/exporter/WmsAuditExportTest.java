package org.jeecg.modules.wms.exporter;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.mapper.WmsExportTaskMapper;
import org.jeecg.modules.wms.service.WmsExportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 审计日志导出（XT-002）。
 *
 * <p>复用 KC-004 的异步任务表，但执行器原先只认库存台账——这里钉住按 taskType 分发；
 * 同时钉住「没有数据不生成空文件」：一个只有表头的 xlsx 会被误读成「这段时间没操作过」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditExportTest {

    private WmsExportTaskMapper taskMapper;
    private WmsLedgerExportService ledgerExportService;
    private WmsAuditExportService auditExportService;
    private WmsExportFileStore fileStore;
    private WmsExcelWriter excelWriter;
    private WmsAuditLogMapper auditLogMapper;

    @BeforeEach
    void setUp() {
        taskMapper = mock(WmsExportTaskMapper.class);
        ledgerExportService = mock(WmsLedgerExportService.class);
        fileStore = mock(WmsExportFileStore.class);
        excelWriter = mock(WmsExcelWriter.class);
        auditLogMapper = mock(WmsAuditLogMapper.class);
        auditExportService = new WmsAuditExportService(auditLogMapper, excelWriter);
    }

    private WmsExportTask task(String taskType) {
        WmsExportTask task = new WmsExportTask();
        task.setId("T1");
        task.setTaskType(taskType);
        task.setQueryParams("{\"v\":1,\"q\":{}}");
        task.setStatus(WmsConstants.EXPORT_STATUS_PENDING);
        task.setCreateBy("admin");
        return task;
    }

    private WmsAuditExportService runner() {
        return auditExportService;
    }

    /** AutoPoi 的写盘动作在单测里换成「一直拉数据源直到返回 null」，行数才有意义。 */
    private void stubWriterPullsSource() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            org.jeecgframework.poi.handler.inter.IExcelExportServer server = invocation.getArgument(2);
            // 只拉一批：mock 的 mapper 每次返回同一个列表，循环下去会一直累加
            server.selectListForExcelExport(null, 1);
            return null;
        }).when(excelWriter).write(any(), any(), any(), any(), any());
    }

    /** AUDIT_LOG 必须走审计导出器，而不是库存台账导出器。 */
    @Test
    void runner_dispatchesByTaskType() throws Exception {
        stubWriterPullsSource();
        when(taskMapper.selectById("T1")).thenReturn(task(WmsConstants.EXPORT_TASK_TYPE_AUDIT_LOG));
        File part = new File("part.tmp");
        when(fileStore.partFile("T1")).thenReturn(part);
        when(fileStore.buildFileName(eq("T1"), any(Date.class))).thenReturn("audit.xlsx");
        File done = new File("audit.xlsx");
        when(fileStore.commit(eq(part), anyString())).thenReturn(done);
        when(auditLogMapper.selectCount(any())).thenReturn(1L);
        when(auditLogMapper.selectList(any())).thenReturn(List.of(new WmsAuditLog().setId("A1")));

        WmsExportTaskRunnerImpl runner = new WmsExportTaskRunnerImpl(
                taskMapper, ledgerExportService, auditExportService, fileStore, new WmsExportProperties());
        runner.execute("T1", "admin");

        verify(excelWriter).write(any(), eq(WmsAuditLog.class), any(), any(), eq(part));
        verify(ledgerExportService, never()).generate(any(), any());
    }

    /** 没有符合条件的日志：任务失败并说明原因，不写文件。 */
    @Test
    void export_emptyResult_doesNotCreateFile() throws Exception {
        when(auditLogMapper.selectCount(any())).thenReturn(0L);
        File target = new File("audit.tmp");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> runner().generate(new WmsAuditQueryDTO(), target));

        assertTrue(ex.getMessage().contains("没有符合条件的日志"), "实际：" + ex.getMessage());
        verify(excelWriter, never()).write(any(), any(), any(), any(), any());
    }

    /** 每条日志写一行，行数回写到任务表。 */
    @Test
    void export_writesRowPerLog() throws Exception {
        stubWriterPullsSource();
        when(auditLogMapper.selectCount(any())).thenReturn(2L);
        when(auditLogMapper.selectList(any())).thenReturn(List.of(
                new WmsAuditLog().setId("A1").setCreateTime(new Date(1000)),
                new WmsAuditLog().setId("A2").setCreateTime(new Date(2000))));

        WmsLedgerExportResult result = runner().generate(new WmsAuditQueryDTO(), new File("audit.tmp"));

        assertEquals(2L, result.rowCount());
        verify(excelWriter).write(any(), eq(WmsAuditLog.class), any(), any(), any());
    }

    /** 未知或无类型的老任务按库存台账处理，不能被新分发逻辑打挂。 */
    @Test
    void runner_unknownTaskType_fallsBackToLedger() throws Exception {
        when(taskMapper.selectById("T2")).thenReturn(task(null));
        File part = new File("part.tmp");
        when(fileStore.partFile("T2")).thenReturn(part);
        when(fileStore.buildFileName(eq("T2"), any(Date.class))).thenReturn("ledger.xlsx");
        File done = new File("ledger.xlsx");
        when(fileStore.commit(eq(part), anyString())).thenReturn(done);
        when(ledgerExportService.generate(any(), eq(part)))
                .thenReturn(new WmsLedgerExportResult(1L, new Date()));

        WmsExportTaskRunnerImpl runner = new WmsExportTaskRunnerImpl(
                taskMapper, ledgerExportService, auditExportService, fileStore, new WmsExportProperties());
        runner.execute("T2", "admin");

        verify(ledgerExportService).generate(any(), eq(part));
        verify(excelWriter, never()).write(any(), any(), any(), any(), any());
    }

    /** 查询条件要透传到取数 SQL（按单号筛时不能整表导出）。 */
    @Test
    void export_appliesQueryConditions() throws Exception {
        stubWriterPullsSource();
        when(auditLogMapper.selectCount(any())).thenReturn(1L);
        when(auditLogMapper.selectList(any())).thenReturn(List.of(new WmsAuditLog().setId("A1")));
        WmsAuditQueryDTO query = new WmsAuditQueryDTO();
        query.setBizNo("MO1");

        runner().generate(query, new File("audit.tmp"));

        verify(auditLogMapper).selectCount(any(Wrapper.class));
        verify(auditLogMapper).selectList(any(Wrapper.class));
    }
}
