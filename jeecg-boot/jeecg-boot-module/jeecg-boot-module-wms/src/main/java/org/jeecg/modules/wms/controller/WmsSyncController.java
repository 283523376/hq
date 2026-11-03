package org.jeecg.modules.wms.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.util.FileDownloadUtils;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncDiffHandleDTO;
import org.jeecg.modules.wms.dto.WmsSyncLogQueryDTO;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncLog;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.exception.WmsSyncExportException;
import org.jeecg.modules.wms.exporter.WmsSyncSnapshotExportService;
import org.jeecg.modules.wms.exporter.WmsSyncSnapshotExportService.SyncExportFile;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSyncDiffService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSyncReconcileService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * @Description: SB-001 上位系统同步对账（spec 2026-11-03 §3）。
 *
 * <p>权限码分四档：执行/查批次/查快照流水/导出用 {@code wms:sync:run}，
 * 差异查询 {@code wms:sync:diff:view}，差异处理 {@code wms:sync:diff:handle}，
 * 发起盘点 {@code wms:sync:diff:stocktake}，同步日志 {@code wms:sync:log:view}。
 *
 * <p>导出是**同步**的（单批次数据量有限），xlsx 落临时文件后流式回写，读完即删。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Tag(name = "WMS-上位系统同步")
@RestController
@RequestMapping("/wms/sync")
@Slf4j
public class WmsSyncController {

    private static final String PERM_RUN = "wms:sync:run";
    private static final String PERM_DIFF_VIEW = "wms:sync:diff:view";
    private static final String PERM_DIFF_HANDLE = "wms:sync:diff:handle";
    private static final String PERM_DIFF_STOCKTAKE = "wms:sync:diff:stocktake";
    private static final String PERM_LOG_VIEW = "wms:sync:log:view";

    private static final String JSON_UTF8 = "application/json;charset=UTF-8";
    private static final String EXPORT_FORMAT_JSON = "json";

    private final IWmsSyncReconcileService reconcileService;
    private final IWmsSyncDiffService diffService;
    private final IWmsSyncLogService syncLogService;
    private final WmsSyncSnapshotMapper snapshotMapper;
    private final WmsSyncFlowMapper flowMapper;
    private final WmsSyncSnapshotExportService exportService;

    public WmsSyncController(IWmsSyncReconcileService reconcileService,
                             IWmsSyncDiffService diffService,
                             IWmsSyncLogService syncLogService,
                             WmsSyncSnapshotMapper snapshotMapper,
                             WmsSyncFlowMapper flowMapper,
                             WmsSyncSnapshotExportService exportService) {
        this.reconcileService = reconcileService;
        this.diffService = diffService;
        this.syncLogService = syncLogService;
        this.snapshotMapper = snapshotMapper;
        this.flowMapper = flowMapper;
        this.exportService = exportService;
    }

    // ================================================================ 批次

    @Operation(summary = "对账-创建并异步执行批次")
    @PostMapping(value = "/run")
    @RequiresPermissions(PERM_RUN)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_SYNC, action = WmsConstants.AUDIT_ACTION_CREATE)
    public Result<String> createRun(@RequestBody(required = false) WmsSyncRunCreateDTO dto) {
        String operator = WmsUtils.currentUsername();
        try {
            String runId = reconcileService.createRun(dto, operator);
            return Result.OK("对账批次已创建", runId);
        } catch (Exception e) {
            log.warn("创建对账批次失败", e);
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "对账-批次分页")
    @GetMapping(value = "/run/page")
    @RequiresPermissions(PERM_RUN)
    public Result<IPage<WmsSyncRun>> runPage(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "status", required = false) String status) {
        return Result.OK(reconcileService.pageRuns(safePageNo(pageNo), safePageSize(pageSize), status));
    }

    @Operation(summary = "对账-批次详情（前端轮询状态）")
    @GetMapping(value = "/run/{id}")
    @RequiresPermissions(PERM_RUN)
    public Result<WmsSyncRun> runDetail(@PathVariable("id") String id) {
        try {
            return Result.OK(reconcileService.requireRun(id));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ================================================================ 快照 / 流水

    @Operation(summary = "对账-库存快照分页（待推送包）")
    @GetMapping(value = "/snapshot/page")
    @RequiresPermissions(PERM_RUN)
    public Result<IPage<WmsSyncSnapshot>> snapshotPage(
            @RequestParam(name = "runId", required = false) String runId,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        if (isBlank(runId)) {
            return Result.error("批次ID不能为空");
        }
        QueryWrapper<WmsSyncSnapshot> wrapper = new QueryWrapper<>();
        wrapper.eq("run_id", runId.trim()).orderByAsc("id");
        return Result.OK(snapshotMapper.selectPage(
                new Page<>(safePageNo(pageNo), safePageSize(pageSize)), wrapper));
    }

    @Operation(summary = "对账-同步流水分页")
    @GetMapping(value = "/flow/page")
    @RequiresPermissions(PERM_RUN)
    public Result<IPage<WmsSyncFlow>> flowPage(
            @RequestParam(name = "runId", required = false) String runId,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        if (isBlank(runId)) {
            return Result.error("批次ID不能为空");
        }
        QueryWrapper<WmsSyncFlow> wrapper = new QueryWrapper<>();
        wrapper.eq("run_id", runId.trim()).orderByAsc("id");
        return Result.OK(flowMapper.selectPage(
                new Page<>(safePageNo(pageNo), safePageSize(pageSize)), wrapper));
    }

    // ================================================================ 导出

    @Operation(summary = "对账-待推送包导出（xlsx 默认 / json）")
    @GetMapping(value = "/snapshot/export")
    @RequiresPermissions(PERM_RUN)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_SYNC, action = WmsConstants.AUDIT_ACTION_EXPORT,
            bizNoSpEL = "#runId")
    public void exportSnapshot(@RequestParam(name = "runId", required = false) String runId,
                               @RequestParam(name = "format", defaultValue = "xlsx") String format,
                               HttpServletResponse response) {
        export(runId, format, response, true);
    }

    @Operation(summary = "对账-同步流水导出（xlsx）")
    @GetMapping(value = "/flow/export")
    @RequiresPermissions(PERM_RUN)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_SYNC, action = WmsConstants.AUDIT_ACTION_EXPORT,
            bizNoSpEL = "#runId")
    public void exportFlow(@RequestParam(name = "runId", required = false) String runId,
                           HttpServletResponse response) {
        export(runId, null, response, false);
    }

    private void export(String runId, String format, HttpServletResponse response, boolean snapshot) {
        if (isBlank(runId)) {
            // 失败必须抛出去：@WmsAudit 切面靠异常记「失败」，吞掉会被记成成功（评审 I-1）
            throw new WmsSyncExportException("批次ID不能为空");
        }
        String id = runId.trim();
        boolean asJson = snapshot && EXPORT_FORMAT_JSON.equalsIgnoreCase(
                format == null ? "" : format.trim());
        File temp = null;
        try {
            temp = asJson
                    ? Files.createTempFile("wms-sync-export-", ".json").toFile()
                    : Files.createTempFile("wms-sync-export-", ".xlsx").toFile();
            SyncExportFile exported = asJson
                    ? exportService.exportJson(id, temp)
                    : (snapshot ? exportService.exportSnapshot(id, temp) : exportService.exportFlow(id, temp));
            if (asJson) {
                writeJsonFile(response, exported);
            } else {
                FileDownloadUtils.downloadFile(response, exported.file().getAbsolutePath(), exported.fileName());
            }
        } catch (Exception e) {
            log.warn("对待推送包导出失败：runId={}, format={}", id, format, e);
            // 统一收敛成专用异常：异常处理器只认它，不会接管权限异常和其它端点的底层异常
            throw e instanceof WmsSyncExportException ? (WmsSyncExportException) e
                    : new WmsSyncExportException(e.getMessage(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /**
     * 导出失败统一回写 {@code Result.error} JSON。
     *
     * <p>只处理 {@link WmsSyncExportException}：范围收窄后，Shiro 的
     * {@code AuthorizationException}、其它端点的底层异常仍交给全局
     * {@code JeecgBootExceptionHandler}（保留 510 未授权响应与 sys_log）。
     *
     * <p>切面只通知控制器方法本身，本方法没有 {@code @WmsAudit}，不会重复写审计。
     */
    @ExceptionHandler(WmsSyncExportException.class)
    public void handleExportFailure(WmsSyncExportException e, HttpServletResponse response) {
        log.warn("对待推送包导出失败：{}", e.getMessage());
        writeJsonError(response, e.getMessage());
    }

    // ================================================================ 差异

    @Operation(summary = "对账-差异清单分页")
    @GetMapping(value = "/diff/page")
    @RequiresPermissions(PERM_DIFF_VIEW)
    public Result<IPage<WmsSyncDiff>> diffPage(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "runId", required = false) String runId,
            @RequestParam(name = "warehouseCode", required = false) String warehouseCode,
            @RequestParam(name = "materialKeyword", required = false) String materialKeyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "diffType", required = false) String diffType) {
        return Result.OK(diffService.pageDiffs(safePageNo(pageNo), safePageSize(pageSize),
                runId, warehouseCode, materialKeyword, status, diffType));
    }

    @Operation(summary = "对账-差异标记处理（已核对/已忽略，备注必填）")
    @PostMapping(value = "/diff/{id}/handle")
    @RequiresPermissions(PERM_DIFF_HANDLE)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_SYNC, action = WmsConstants.AUDIT_ACTION_UPDATE,
            bizNoSpEL = "#id")
    public Result<String> handle(@PathVariable("id") String id,
                                 @RequestBody(required = false) WmsSyncDiffHandleDTO dto) {
        if (dto == null) {
            return Result.error("处理参数不能为空");
        }
        try {
            diffService.handle(id, dto.getTargetStatus(), dto.getRemark(), WmsUtils.currentUsername());
            return Result.OK("已处理");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "对账-由差异发起盘点")
    @PostMapping(value = "/diff/{id}/create-stocktake")
    @RequiresPermissions(PERM_DIFF_STOCKTAKE)
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_SYNC, action = WmsConstants.AUDIT_ACTION_CREATE,
            bizNoSpEL = "#id")
    public Result<WmsStocktakeOrder> createStocktake(@PathVariable("id") String id) {
        try {
            return Result.OK("盘点草稿已创建",
                    diffService.createStocktake(id, WmsUtils.currentUsername()));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    // ================================================================ 同步日志

    @Operation(summary = "上位系统同步日志分页")
    @GetMapping(value = "/log/page")
    @RequiresPermissions(PERM_LOG_VIEW)
    public Result<IPage<WmsSyncLog>> logPage(WmsSyncLogQueryDTO query,
                                             @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                             @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        WmsSyncLogQueryDTO q = query == null ? new WmsSyncLogQueryDTO() : query;
        QueryWrapper<WmsSyncLog> wrapper = new QueryWrapper<>();
        wrapper.eq(!isBlank(q.getDirection()), "direction", trim(q.getDirection()));
        wrapper.eq(!isBlank(q.getObjectType()), "object_type", trim(q.getObjectType()));
        wrapper.like(!isBlank(q.getObjectNo()), "object_no", trim(q.getObjectNo()));
        wrapper.eq(!isBlank(q.getTarget()), "target", trim(q.getTarget()));
        wrapper.eq(!isBlank(q.getStatus()), "status", trim(q.getStatus()));
        wrapper.ge(q.getStartTime() != null, "create_time", q.getStartTime());
        wrapper.le(q.getEndTime() != null, "create_time", q.getEndTime());
        wrapper.orderByDesc("create_time").orderByDesc("id");
        return Result.OK(syncLogService.page(
                new Page<>(safePageNo(pageNo), safePageSize(pageSize)), wrapper));
    }

    // ================================================================ 工具

    private static int safePageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private static int safePageSize(Integer pageSize) {
        int size = pageSize == null || pageSize < 1 ? 10 : pageSize;
        return Math.min(size, WmsConstants.INVENTORY_LEDGER_MAX_PAGE_SIZE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** JSON 导出：attachment + .json，浏览器直接落盘。 */
    private static void writeJsonFile(HttpServletResponse response, SyncExportFile exported) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType(JSON_UTF8);
        response.setHeader("Content-Disposition", "attachment;filename="
                + URLEncoder.encode(exported.fileName(), StandardCharsets.UTF_8));
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        Files.copy(exported.file().toPath(), response.getOutputStream());
        response.flushBuffer();
    }

    /** 导出失败时回写 JSON 错误（与台账下载端点一致，避免前端拿到半截 xlsx）。 */
    private static void writeJsonError(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(JSON_UTF8);
            response.getWriter().write(JSON.toJSONString(Result.error(message)));
        } catch (Exception e) {
            // getWriter 在 response 已提交时会抛 IllegalStateException，不能让回写错误再逃逸
            log.warn("导出失败时回写错误信息也失败了：{}", e.getMessage());
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            log.warn("删除导出临时文件失败：{}（{}）", file.getAbsolutePath(), e.getMessage());
        }
    }
}
