package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsExportTask;
import org.jeecg.modules.wms.service.IWmsExportTaskService;
import org.jeecg.modules.wms.service.IWmsInventoryLedgerService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 库存台账（KC-004）。
 *
 * <p>查询是给人看的接口，pageSize 有硬上限；几十万行的全量导出走
 * {@code POST /export} 的异步任务，不要指望在这里分页翻完。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-29
 */
@Tag(name = "WMS-库存台账")
@RestController
@RequestMapping("/wms/inventoryLedger")
@Slf4j
public class WmsInventoryLedgerController {

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
        // 账号只认登录人，请求里带什么 createBy 都不看
        return Result.OK(exportTaskService.pageMine(WmsUtils.currentUsername(),
                pageNo == null || pageNo < 1 ? 1 : pageNo,
                pageSize == null || pageSize < 1 ? 10 : pageSize));
    }
}
