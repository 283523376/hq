package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsProductionCloseDTO;
import org.jeecg.modules.wms.dto.WmsProductionManualCompleteDTO;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.service.IWmsProductionTaskService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

/**
 * @Description: 生产入库（YX-WMS-RK-002）：扫码、提交、下发、回执后的转人工/关单/同步。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-生产入库")
@RestController
@RequestMapping("/wms/production")
@Slf4j
public class WmsProductionController {

    @Autowired
    private IWmsProductionInboundService service;

    @Autowired
    private IWmsWorkOrderService workOrderService;

    @Autowired
    private IWmsProductionTaskService taskService;

    @Autowired
    private IWmsProductionSyncService syncService;

    @RequiresPermissions("wms:production:scan")
    @Operation(summary = "生产入库-扫码调出工单信息")
    @GetMapping(value = "/work-order")
    public Result<WmsWorkOrderInfoVO> workOrder(
            @RequestParam(name = "workOrderNo") String workOrderNo) {
        try {
            return Result.OK(workOrderService.scan(workOrderNo));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:submit")
    @AutoLog(value = "生产入库-提交", operateType = 2)
    @Operation(summary = "生产入库-提交（含事务提交后的任务下发）")
    @PostMapping(value = "/submit")
    public Result<WmsProductionInbound> submit(@RequestBody WmsProductionSubmitDTO dto) {
        try {
            WmsProductionInbound record = service.submit(dto);
            if (record.getGoodQty() != null && record.getGoodQty().signum() > 0) {
                // 事务已提交（service.submit 的代理已返回），异步调用外部设备接口，
                // 避免 RMS/WCS 超时把提交请求拖到前端 axios 超时
                taskService.dispatchInboundAsync(record.getId());
            } else {
                // 良品为 0：提交即入库，立即同步，不等 5 分钟补偿
                WmsOrderSyncAttempt attempt = syncService.syncInbound(record.getId(), true);
                service.persistSyncResult(record.getId(), attempt);
            }
            record = service.getById(record.getId());
            return Result.OK("提交成功", record);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:dispatch")
    @Operation(summary = "生产入库-重新下发任务")
    @PostMapping(value = "/{id}/dispatch")
    public Result<WmsProductionInbound> dispatch(@PathVariable("id") String id) {
        try {
            // 人工触发的重新下发保持同步：状态不允许时要把错误明确返回给用户
            taskService.dispatchInbound(id);
            return Result.OK("已重新下发", service.getById(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:dispatch")
    @Operation(summary = "生产入库-工作站强制入库（RMS /api/v2/task/inbound/force）")
    @PostMapping(value = "/{id}/force-inbound")
    public Result<WmsTaskDispatchResult> forceInbound(@PathVariable("id") String id) {
        try {
            WmsTaskDispatchResult result = taskService.forceInbound(id);
            return result.isSuccess()
                    ? Result.OK("强制入库已受理", result)
                    : Result.error("强制入库失败：" + result.getErrorMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:manual")
    @Operation(summary = "生产入库-转人工完成")
    @PostMapping(value = "/{id}/manual-complete")
    public Result<WmsOrderSyncAttempt> manualComplete(@PathVariable("id") String id,
                                                      @RequestBody(required = false) WmsProductionManualCompleteDTO dto) {
        try {
            service.manualComplete(id, dto);
            WmsOrderSyncAttempt attempt = syncService.syncInbound(id, true);
            service.persistSyncResult(id, attempt);
            return attempt.isSuccess()
                    ? Result.OK("转人工完成并同步成功", attempt)
                    : Result.error("转人工完成，但同步失败：" + attempt.getErrorMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:close")
    @Operation(summary = "生产入库-关单")
    @PostMapping(value = "/{id}/close")
    public Result<Void> close(@PathVariable("id") String id,
                              @RequestBody(required = false) WmsProductionCloseDTO dto) {
        try {
            service.close(id, dto == null ? null : dto.getReason());
            try {
                taskService.cancelInbound(id);
            } catch (Exception e) {
                log.warn("关单后取消设备任务失败（不影响关单）：{}", e.getMessage());
            }
            return Result.OK("关单成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:production:sync")
    @Operation(summary = "生产入库-手动重试同步")
    @PostMapping(value = "/{id}/sync")
    public Result<WmsOrderSyncAttempt> resync(@PathVariable("id") String id) {
        try {
            WmsProductionInbound record = service.getById(id);
            if (record == null) {
                return Result.error("生产入库记录不存在");
            }
            if (!WmsConstants.PROD_STATUS_INBOUND.equals(record.getStatus())) {
                return Result.error("仅已入库记录可同步");
            }
            Date staleBefore = service.syncStaleBefore();
            if (service.claimForSync(id, staleBefore) == 0) {
                return Result.error("记录正在同步中，请稍后重试");
            }
            WmsOrderSyncAttempt attempt = syncService.syncInbound(id, true);
            service.persistSyncResult(id, attempt);
            return attempt.isSuccess()
                    ? Result.OK("同步成功", attempt)
                    : Result.error("同步失败：" + attempt.getErrorMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "生产入库-分页列表")
    @GetMapping(value = "/list")
    public Result<IPage<WmsProductionInbound>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            WmsProductionInbound query) {
        return Result.OK(service.listPage(pageNo, pageSize, query));
    }

    @Operation(summary = "生产入库-详情")
    @GetMapping(value = "/{id}")
    public Result<WmsProductionInbound> detail(@PathVariable("id") String id) {
        try {
            return Result.OK(service.detail(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
