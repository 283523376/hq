package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundCloseDTO;
import org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSampleCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundReviewDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSalesCreateDTO;
import org.jeecg.modules.wms.dto.WmsSalesOrderSyncDTO;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.service.IWmsSubcontractOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.vo.WmsOutboundConfirmVO;
import org.jeecg.modules.wms.vo.WmsOutboundDetailVO;
import org.jeecg.modules.wms.vo.WmsOutboundScanVO;
import org.jeecg.modules.wms.vo.WmsSubcontractSyncResultVO;
import org.jeecg.modules.wms.vo.WmsSalesOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 出库管理（CK-001 生产领料 / CK-002 销售出库）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Tag(name = "WMS-出库管理")
@RestController
@RequestMapping("/wms/outbound")
@Slf4j
public class WmsOutboundController extends JeecgController<WmsOutboundOrder, IWmsOutboundService> {

    @Autowired
    private IWmsOutboundSyncService syncService;

    @Autowired
    private IWmsOutboundTaskService taskService;

    @Autowired
    private IWmsSubcontractOrderSyncService subcontractOrderSyncService;

    @Operation(summary = "出库-同步金蝶委外订单为外协出库草稿")
    @PostMapping(value = "/sync-subcontract")
    @RequiresPermissions("wms:outbound:add")
    public Result<WmsSubcontractSyncResultVO> syncSubcontract(
            @RequestParam(name = "top", defaultValue = "50") Integer top) {
        try {
            return Result.OK("同步完成", subcontractOrderSyncService.syncFromKingdee(top));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-创建样品出库单")
    @PostMapping(value = "/sample")
    @RequiresPermissions("wms:outbound:add")
    public Result<WmsOutboundOrder> createSample(@RequestBody WmsOutboundSampleCreateDTO dto) {
        try {
            return Result.OK("创建成功", service.createSample(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-扫码校验工单并调出需求明细（CK-001）")
    @GetMapping(value = "/pick/scan")
    @RequiresPermissions("wms:outbound:scan")
    public Result<WmsOutboundScanVO> scanPick(@RequestParam("workOrderNo") String workOrderNo) {
        try {
            return Result.OK(service.scanPick(workOrderNo));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-创建领料出库单（CK-001）")
    @PostMapping(value = "/pick")
    @RequiresPermissions("wms:outbound:add")
    public Result<WmsOutboundOrder> createPick(@RequestBody WmsOutboundPickCreateDTO dto) {
        try {
            return Result.OK("创建成功", service.createPick(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-创建销售订单与出库单（CK-002）")
    @PostMapping(value = "/sales")
    @RequiresPermissions("wms:outbound:sales")
    public Result<WmsOutboundOrder> createSales(@RequestBody WmsOutboundSalesCreateDTO dto) {
        try {
            WmsOutboundOrder order = service.createSales(dto);
            return Result.OK("创建成功（基于 ERP 销售订单）", service.getById(order.getId()));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-分页列表")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:outbound:list")
    public Result<IPage<WmsOutboundOrder>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "outboundType", required = false) String outboundType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return Result.OK(service.listPage(pageNo, pageSize, outboundType, status, keyword));
    }

    @Operation(summary = "出库-单据详情")
    @GetMapping(value = "/{id}")
    @RequiresPermissions("wms:outbound:list")
    public Result<WmsOutboundDetailVO> detail(@PathVariable("id") String id) {
        try {
            return Result.OK(service.detail(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-分配库位/料箱")
    @PostMapping(value = "/{id}/allocate")
    @RequiresPermissions("wms:outbound:allocate")
    public Result<Void> allocate(@PathVariable("id") String id) {
        try {
            service.allocate(id);
            return Result.OK("分配成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-下发设备任务")
    @PostMapping(value = "/{id}/dispatch")
    @RequiresPermissions("wms:outbound:dispatch")
    public Result<Void> dispatch(@PathVariable("id") String id) {
        try {
            service.dispatch(id);
            return Result.OK("下发成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-复核（CK-002）")
    @PostMapping(value = "/{id}/review")
    @RequiresPermissions("wms:outbound:review")
    public Result<Void> review(@PathVariable("id") String id,
                               @RequestBody WmsOutboundReviewDTO dto) {
        try {
            service.review(id, dto);
            return Result.OK("复核通过", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-取料确认（CK-001）")
    @PostMapping(value = "/{id}/confirm-pick")
    @RequiresPermissions("wms:outbound:confirm")
    public Result<WmsOutboundConfirmVO> confirmPick(@PathVariable("id") String id,
                                                    @RequestBody WmsOutboundConfirmDTO dto) {
        try {
            WmsOutboundConfirmVO vo = service.confirmPick(id, dto);
            return syncAfterConfirm(id, vo);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-确认出库（CK-002）")
    @PostMapping(value = "/{id}/confirm")
    @RequiresPermissions("wms:outbound:confirm")
    public Result<WmsOutboundConfirmVO> confirm(@PathVariable("id") String id,
                                                @RequestBody WmsOutboundConfirmDTO dto) {
        try {
            WmsOutboundConfirmVO vo = service.confirm(id, dto);
            return syncAfterConfirm(id, vo);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-手工重新同步出库单")
    @PostMapping(value = "/{id}/sync")
    @RequiresPermissions("wms:outbound:sync")
    public Result<WmsOrderSyncAttempt> resync(@PathVariable("id") String id) {
        try {
            WmsOutboundOrder order = service.getById(id);
            if (order == null) {
                return Result.error("单据不存在");
            }
            if (!WmsConstants.OUTBOUND_STATUS_COMPLETED.equals(order.getStatus())) {
                return Result.error("仅已完成单据可同步 ERP");
            }
            WmsOrderSyncAttempt attempt = syncService.sync(id, true);
            syncService.persistResult(id, attempt);
            return attempt.isSuccess()
                    ? Result.OK(attempt.isMocked() ? "模拟同步成功（未启用金蝶）" : "同步成功", attempt)
                    : Result.error("同步失败：" + attempt.getErrorMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-预览 ERP 销售订单（含剩余可出数量）")
    @GetMapping(value = "/sales-order")
    @RequiresPermissions("wms:outbound:sales")
    public Result<WmsSalesOrderVO> previewSalesOrder(@RequestParam("no") String no) {
        try {
            return Result.OK(service.previewSalesOrder(no));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-拉取 ERP 销售订单（按单号或时间段）")
    @PostMapping(value = "/sales-order/sync")
    @RequiresPermissions("wms:outbound:sales-sync")
    public Result<String> syncSalesOrder(@RequestBody WmsSalesOrderSyncDTO dto) {
        try {
            int count = service.syncSalesOrder(dto);
            return Result.OK("已拉取 " + count + " 张销售订单", String.valueOf(count));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "出库-关单（释放锁定）")
    @PostMapping(value = "/{id}/close")
    @RequiresPermissions("wms:outbound:close")
    public Result<Void> close(@PathVariable("id") String id,
                              @RequestBody(required = false) WmsOutboundCloseDTO dto) {
        // 先取消设备任务再释放锁定：否则堆垛机可能还在取货，库存却已可被其它单据分配
        try {
            taskService.cancel(id);
        } catch (RuntimeException e) {
            log.warn("出库单[{}]关单前取消设备任务失败（继续关单）：{}", id, e.getMessage());
        }
        try {
            service.close(id, dto == null ? null : dto.getReason());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
        return Result.OK("关单成功", null);
    }

    /**
     * 确认出库成功后同步出库单；同步失败只影响返回文案，不回滚库存、不改单据状态。
     */
    private Result<WmsOutboundConfirmVO> syncAfterConfirm(String id, WmsOutboundConfirmVO vo) {
        if (!vo.isConfirmed()) {
            // 销售订单未同步：等待操作员确认，不能自行继续
            return Result.OK(vo);
        }
        WmsOrderSyncAttempt attempt = syncService.sync(id, true);
        syncService.persistResult(id, attempt);
        vo.setMessage(attempt.isSuccess()
                ? (attempt.isMocked() ? "出库完成，已模拟同步（未启用金蝶）" : "出库完成，已同步金蝶")
                : "出库完成，但出库单同步失败：" + attempt.getErrorMessage());
        return Result.OK(vo.getMessage(), vo);
    }
}
