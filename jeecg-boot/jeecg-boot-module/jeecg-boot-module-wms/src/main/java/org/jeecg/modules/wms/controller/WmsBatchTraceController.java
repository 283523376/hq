package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.service.IWmsBatchTraceService;
import org.jeecg.modules.wms.vo.WmsBatchFlowVO;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Description: 批次追溯（KC-001 任务 5）。
 *
 * <p>只读：当前库存明细 + 跨 5 类单据的流转记录。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Tag(name = "WMS-批次追溯")
@RestController
@RequestMapping("/wms/inventory/batch")
@Slf4j
public class WmsBatchTraceController {

    private final IWmsBatchTraceService traceService;

    public WmsBatchTraceController(IWmsBatchTraceService traceService) {
        this.traceService = traceService;
    }

    @Operation(summary = "批次-当前库存明细")
    @GetMapping(value = "/stock")
    @RequiresPermissions("wms:inventory:batch")
    public Result<List<WmsInventoryStockRowVO>> stock(@RequestParam("batchNo") String batchNo) {
        try {
            return Result.OK(traceService.batchStock(batchNo));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "批次-流转记录（时间倒序）")
    @GetMapping(value = "/flow")
    @RequiresPermissions("wms:inventory:batch")
    public Result<List<WmsBatchFlowVO>> flow(@RequestParam("batchNo") String batchNo,
                                             @RequestParam(name = "limit", defaultValue = "200") Integer limit) {
        try {
            return Result.OK(traceService.batchFlow(batchNo, limit));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
