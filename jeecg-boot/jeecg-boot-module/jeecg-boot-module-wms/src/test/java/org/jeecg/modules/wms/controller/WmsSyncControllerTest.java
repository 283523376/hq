package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncDiffHandleDTO;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.exporter.WmsSyncSnapshotExportService;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSyncDiffService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSyncReconcileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 对账 REST 接口单测。
 *
 * <p>对账页面能触发真实的金蝶查询、能由差异发起盘点，权限码必须逐个钉死——
 * 前端隐藏按钮不是权限。导出端点同样要有导出类审计动作。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncControllerTest {

    private IWmsSyncReconcileService reconcileService;
    private IWmsSyncDiffService diffService;
    private IWmsSyncLogService syncLogService;
    private WmsSyncSnapshotMapper snapshotMapper;
    private WmsSyncFlowMapper flowMapper;
    private WmsSyncSnapshotExportService exportService;
    private WmsSyncController controller;

    @BeforeEach
    void setUp() {
        reconcileService = mock(IWmsSyncReconcileService.class);
        diffService = mock(IWmsSyncDiffService.class);
        syncLogService = mock(IWmsSyncLogService.class);
        snapshotMapper = mock(WmsSyncSnapshotMapper.class);
        flowMapper = mock(WmsSyncFlowMapper.class);
        exportService = mock(WmsSyncSnapshotExportService.class);
        controller = new WmsSyncController(reconcileService, diffService, syncLogService,
                snapshotMapper, flowMapper, exportService);
    }

    /** 每个端点都必须挂上约定的权限码，一个都不能漏。 */
    @Test
    void everyEndpoint_declaresExpectedPermission() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("createRun", "wms:sync:run"),
                Map.entry("runPage", "wms:sync:run"),
                Map.entry("runDetail", "wms:sync:run"),
                Map.entry("snapshotPage", "wms:sync:run"),
                Map.entry("flowPage", "wms:sync:run"),
                Map.entry("exportSnapshot", "wms:sync:run"),
                Map.entry("exportFlow", "wms:sync:run"),
                Map.entry("diffPage", "wms:sync:diff:view"),
                Map.entry("handle", "wms:sync:diff:handle"),
                Map.entry("createStocktake", "wms:sync:diff:stocktake"),
                Map.entry("logPage", "wms:sync:log:view"));

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Method method = findMethod(entry.getKey());
            RequiresPermissions annotation = method.getAnnotation(RequiresPermissions.class);
            assertNotNull(annotation, entry.getKey() + " 缺少 @RequiresPermissions");
            assertEquals(entry.getValue(), annotation.value()[0], entry.getKey() + " 权限码不对");
        }
    }

    /** 写端点与导出端点必须留痕；动作码直接取常量，避免把手写字符串写歪。 */
    @Test
    void writeAndExportEndpoints_declareAuditAction() {
        assertAudit("createRun", WmsConstants.AUDIT_ACTION_CREATE, "");
        assertAudit("handle", WmsConstants.AUDIT_ACTION_UPDATE, "#id");
        assertAudit("createStocktake", WmsConstants.AUDIT_ACTION_CREATE, "#id");
        assertAudit("exportSnapshot", WmsConstants.AUDIT_ACTION_EXPORT, "#runId");
        assertAudit("exportFlow", WmsConstants.AUDIT_ACTION_EXPORT, "#runId");
    }

    /** create-run 必须把服务层生成的批次 ID 原样回给前端，前端靠它轮询状态。 */
    @Test
    void createRun_returnsRunId() {
        when(reconcileService.createRun(any(), any())).thenReturn("RUN-1");

        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType(WmsConstants.SYNC_SCOPE_TODAY);
        Result<String> result = controller.createRun(dto);

        assertTrue(result.isSuccess());
        assertEquals("RUN-1", result.getResult());
        verify(reconcileService).createRun(any(), any());
    }

    /** 服务层拒绝（已有执行中批次）时，原因要逐字透出，不能变成 500。 */
    @Test
    void createRun_failure_returnsError() {
        when(reconcileService.createRun(any(), any()))
                .thenThrow(new RuntimeException("已有对账批次在执行中，请稍后再试"));

        Result<String> result = controller.createRun(new WmsSyncRunCreateDTO());

        assertFalse(result.isSuccess());
        assertEquals("已有对账批次在执行中，请稍后再试", result.getMessage());
    }

    /** 差异处理异常（状态非法/备注为空）必须透出 Result.error。 */
    @Test
    void handle_failure_returnsError() {
        doThrow(new RuntimeException("只有「待核对」的差异可以处理，当前状态：已核对"))
                .when(diffService).handle(eq("D1"), eq(WmsConstants.DIFF_STATUS_CHECKED), eq("已核对"), any());

        WmsSyncDiffHandleDTO dto = new WmsSyncDiffHandleDTO();
        dto.setTargetStatus(WmsConstants.DIFF_STATUS_CHECKED);
        dto.setRemark("已核对");
        Result<String> result = controller.handle("D1", dto);

        assertFalse(result.isSuccess());
        assertEquals("只有「待核对」的差异可以处理，当前状态：已核对", result.getMessage());
    }

    /** 入参为空时直接拒绝，不要把一个没人看懂的 NPE 抛给前端。 */
    @Test
    void handle_blankDto_rejected() {
        Result<String> result = controller.handle("D1", null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getMessage());
    }

    /** 发起盘点成功后要回传盘点单，前端据此跳详情。 */
    @Test
    void createStocktake_returnsOrder() {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setStocktakeNo("PD20261103001");
        when(diffService.createStocktake(eq("D1"), any())).thenReturn(order);

        Result<WmsStocktakeOrder> result = controller.createStocktake("D1");

        assertTrue(result.isSuccess());
        assertEquals("PD20261103001", result.getResult().getStocktakeNo());
    }

    /** 批次不存在时详情端点不能抛出到全局异常处理器（那里会加前缀）。 */
    @Test
    void runDetail_missing_returnsError() {
        when(reconcileService.requireRun("NOPE")).thenThrow(new RuntimeException("对账批次不存在：NOPE"));

        Result<WmsSyncRun> result = controller.runDetail("NOPE");

        assertFalse(result.isSuccess());
        assertEquals("对账批次不存在：NOPE", result.getMessage());
    }

    /** 快照/流水分页必须带 runId：不传就是全表扫，接口层直接挡掉。 */
    @Test
    void snapshotPage_blankRunId_rejected() {
        assertFalse(controller.snapshotPage("  ", 1, 10).isSuccess());
        assertFalse(controller.flowPage("  ", 1, 10).isSuccess());
    }

    /** 差异分页透传全部筛选条件。 */
    @Test
    void diffPage_passesAllFilters() {
        when(diffService.pageDiffs(2, 20, "R1", "D.01", "铜带", "待核对", "多账"))
                .thenReturn(new Page<>());

        Result<?> result = controller.diffPage(2, 20, "R1", "D.01", "铜带", "待核对", "多账");

        assertTrue(result.isSuccess());
        verify(diffService).pageDiffs(2, 20, "R1", "D.01", "铜带", "待核对", "多账");
    }

    /** 同步日志的时间区间参数必须能绑定 "yyyy-MM-dd HH:mm:ss"，否则查询页 400。 */
    @Test
    void logQueryDto_timeFields_areBindable() throws Exception {
        for (String fieldName : new String[]{"startTime", "endTime"}) {
            Field field = Class.forName("org.jeecg.modules.wms.dto.WmsSyncLogQueryDTO")
                    .getDeclaredField(fieldName);
            org.springframework.format.annotation.DateTimeFormat fmt =
                    field.getAnnotation(org.springframework.format.annotation.DateTimeFormat.class);
            assertNotNull(fmt, fieldName + " 缺少 @DateTimeFormat");
            assertEquals("yyyy-MM-dd HH:mm:ss", fmt.pattern());
        }
    }

    /** 日志查询把结果包成 Result，且筛选值原样落到 wrapper（这里只断言不抛异常、成功）。 */
    @Test
    void logPage_returnsPageResult() {
        org.jeecg.modules.wms.dto.WmsSyncLogQueryDTO query =
                new org.jeecg.modules.wms.dto.WmsSyncLogQueryDTO();
        query.setDirection(WmsConstants.SYNC_LOG_DIRECTION_WMS_TO_ERP);
        query.setObjectNo("SB20261103");

        when(syncLogService.page(any(), any())).thenReturn(new Page<>());

        Result<?> result = controller.logPage(query, 1, 10);

        assertTrue(result.isSuccess());
        verify(syncLogService).page(any(), any());
    }

    private void assertAudit(String methodName, String action, String bizNoSpEL) {
        WmsAudit audit = findMethod(methodName).getAnnotation(WmsAudit.class);
        assertNotNull(audit, methodName + " 缺少 @WmsAudit");
        assertEquals(WmsConstants.AUDIT_BIZ_SYNC, audit.bizType(), methodName + " bizType 不对");
        assertEquals(action, audit.action(), methodName + " action 不对");
        assertEquals(bizNoSpEL, audit.bizNoSpEL(), methodName + " bizNoSpEL 不对");
    }

    private Method findMethod(String name) {
        for (Method method : WmsSyncController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("找不到端点方法：" + name);
    }
}
