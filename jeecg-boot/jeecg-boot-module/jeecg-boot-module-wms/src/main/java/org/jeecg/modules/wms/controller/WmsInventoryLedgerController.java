package org.jeecg.modules.wms.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.util.FileDownloadUtils;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.service.IWmsExportTaskService;
import org.jeecg.modules.wms.service.IWmsInventoryLedgerService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;

/**
 * @Description: 库存台账（KC-004）。
 *
 * <p>查询是给人看的接口，pageSize 有硬上限；几十万行的全量导出走
 * {@code POST /export} 的异步任务，不要指望在这里分页翻完。
 *
 * <p>导出任务的归属一律按登录人判定（{@code WmsUtils.currentUsername()}），请求里带什么账号都不看。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Tag(name = "WMS-库存台账")
@RestController
@RequestMapping("/wms/inventoryLedger")
@Slf4j
public class WmsInventoryLedgerController {

    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private final IWmsInventoryLedgerService ledgerService;
    private final IWmsExportTaskService exportTaskService;

    public WmsInventoryLedgerController(IWmsInventoryLedgerService ledgerService,
                                        IWmsExportTaskService exportTaskService) {
        this.ledgerService = ledgerService;
        this.exportTaskService = exportTaskService;
    }

    @AutoLog(value = "库存-台账查询", operateType = 1)
    @Operation(summary = "库存台账-分页查询")
    @GetMapping(value = "/page")
    @RequiresPermissions("wms:inventory:ledger")
    public Result<IPage<WmsInventoryLedgerVO>> page(WmsInventoryLedgerQueryDTO query) {
        return Result.OK(ledgerService.queryPage(query));
    }

    @AutoLog(value = "库存-台账导出", operateType = 1)
    @Operation(summary = "库存台账-提交导出任务")
    @PostMapping(value = "/export")
    @RequiresPermissions("wms:inventory:export")
    public Result<String> export(@RequestBody(required = false) WmsInventoryLedgerQueryDTO query) {
        try {
            String taskId = exportTaskService.createLedgerExport(query, WmsUtils.currentUsername());
            return Result.OK("导出任务已提交", taskId);
        } catch (RuntimeException e) {
            // 拒绝文案逐字透出，不要交给全局异常处理器加前缀
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "我的导出任务-分页")
    @GetMapping(value = "/export/task/page")
    @RequiresPermissions("wms:inventory:export")
    public Result<IPage<WmsExportTask>> taskPage(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        return Result.OK(exportTaskService.pageMine(WmsUtils.currentUsername(),
                pageNo == null || pageNo < 1 ? 1 : pageNo,
                pageSize == null || pageSize < 1 ? 10 : pageSize));
    }

    /**
     * 下载已生成的台账。
     *
     * <p>前端用 blob 方式请求，出错时也要回一段 JSON（而不是让 HTTP 500 掉），
     * 否则前端只会弹「文件下载失败」，看不出到底是没有权限还是文件过期。
     */
    @Operation(summary = "导出任务-下载")
    @GetMapping(value = "/export/task/{id}/download")
    @RequiresPermissions("wms:inventory:export")
    public void download(@PathVariable("id") String id, HttpServletResponse response) {
        try {
            File file = exportTaskService.resolveDownload(id, WmsUtils.currentUsername());
            response.reset();
            FileDownloadUtils.downloadFile(response, file.getAbsolutePath(), file.getName());
        } catch (RuntimeException e) {
            writeJsonError(response, e.getMessage());
        }
    }

    @Operation(summary = "导出任务-重试")
    @PostMapping(value = "/export/task/{id}/retry")
    @RequiresPermissions("wms:inventory:export")
    public Result<String> retry(@PathVariable("id") String id) {
        try {
            exportTaskService.retry(id, WmsUtils.currentUsername());
            return Result.OK("已重新提交");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "导出任务-删除")
    @DeleteMapping(value = "/export/task/{id}")
    @RequiresPermissions("wms:inventory:export")
    public Result<String> delete(@PathVariable("id") String id) {
        try {
            exportTaskService.deleteTask(id, WmsUtils.currentUsername());
            return Result.OK("已删除");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    private void writeJsonError(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(JSON_UTF8);
            response.getWriter().write(JSON.toJSONString(Result.error(message)));
        } catch (IOException e) {
            log.warn("下载失败时回写错误信息也失败了：{}", e.getMessage());
        }
    }
}
