package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsReceiveDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsInboundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 采购入库（RK-001）。收料/分配/上架/作废/同步随计划任务逐步挂载。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-采购入库")
@RestController
@RequestMapping("/wms/inbound")
@Slf4j
public class WmsInboundController extends JeecgController<WmsInboundOrder, IWmsInboundService> {

    @Operation(summary = "入库-扫码收料（生成收料通知单）")
    @PostMapping(value = "/receive")
    public Result<WmsInboundOrder> receive(@RequestBody WmsReceiveDTO dto) {
        try {
            return Result.OK("收料成功", service.receive(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "入库-自动分配库位")
    @PostMapping(value = "/{id}/auto-allocate")
    public Result<org.jeecg.modules.wms.vo.WmsAllocateResultVO> autoAllocate(@PathVariable("id") String id) {
        try {
            return Result.OK(service.autoAllocate(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "入库-空闲库位查询（分配弹窗数据源）")
    @GetMapping(value = "/free-locations")
    public Result<java.util.List<org.jeecg.modules.wms.entity.WmsLocation>> freeLocations(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestParam(name = "warehouseType", required = false) String warehouseType) {
        String type = warehouseType;
        if (org.jeecg.common.util.oConvertUtils.isEmpty(type) && oConvertUtils.isNotEmpty(materialId)) {
            org.jeecg.modules.wms.entity.WmsMaterial material = materialService.getById(materialId);
            type = material == null ? null : resolveWarehouseTypeForMaterial(material);
        }
        return Result.OK(locationService.listFreeByWarehouseType(type));
    }

    @Operation(summary = "入库-上架确认（库存/占用/自动审核/触发同步）")
    @PostMapping(value = "/{id}/putaway")
    public Result<Void> putaway(@PathVariable("id") String id, @RequestBody org.jeecg.modules.wms.dto.WmsPutawayDTO dto) {
        try {
            service.putaway(id, dto);
            return Result.OK("上架成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "入库-手动重试 ERP 同步")
    @PostMapping(value = "/{id}/sync")
    public Result<org.jeecg.modules.wms.sync.WmsOrderSyncAttempt> resync(@PathVariable("id") String id) {
        try {
            WmsInboundOrder order = service.getById(id);
            if (order == null) {
                return Result.error("单据不存在");
            }
            if (!org.jeecg.modules.wms.constant.WmsConstants.INBOUND_STATUS_AUDITED
                    .equals(order.getInboundStatus())) {
                return Result.error("仅已审核单据可同步 ERP");
            }
            // 与补偿任务共用乐观认领：避免用户点「重试同步」时定时任务同时向 ERP 重复投递
            if (service.claimForSync(id, service.syncStaleBefore()) == 0) {
                return Result.error("单据正在同步中，请稍后重试");
            }
            org.jeecg.modules.wms.sync.WmsOrderSyncAttempt attempt =
                    inboundSyncService.syncInbound(order, true);
            service.persistSyncResult(id, attempt);
            return attempt.isSuccess()
                    ? Result.OK("同步成功", attempt)
                    : Result.error("同步失败：" + attempt.getErrorMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "入库-分页列表")
    @GetMapping(value = "/list")
    public Result<com.baomidou.mybatisplus.core.metadata.IPage<WmsInboundOrder>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "inboundStatus", required = false) String inboundStatus,
            @RequestParam(name = "orderNo", required = false) String orderNo,
            @RequestParam(name = "supplierName", required = false) String supplierName) {
        return Result.OK(service.listPage(pageNo, pageSize, inboundStatus, orderNo, supplierName));
    }

    @Operation(summary = "入库-单据详情")
    @GetMapping(value = "/{id}")
    public Result<org.jeecg.modules.wms.vo.WmsInboundDetailVO> detail(@PathVariable("id") String id) {
        try {
            return Result.OK(service.detail(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "入库-作废")
    @PostMapping(value = "/{id}/void")
    public Result<Void> voidOrder(@PathVariable("id") String id,
                                  @RequestBody org.jeecg.modules.wms.dto.WmsVoidDTO body) {
        try {
            service.voidOrder(id, body == null ? null : body.getReason());
            return Result.OK("作废成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Autowired
    private org.jeecg.modules.wms.service.IWmsInboundSyncService inboundSyncService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsMaterialService materialService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsLocationService locationService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsMaterialCategoryService categoryService;

    /** 物料 → 目标库区类型（统一走 {@link org.jeecg.modules.wms.util.WmsInboundRouting}）。 */
    private String resolveWarehouseTypeForMaterial(org.jeecg.modules.wms.entity.WmsMaterial material) {
        org.jeecg.modules.wms.entity.WmsMaterialCategory category =
                oConvertUtils.isEmpty(material.getCategoryId()) ? null : categoryService.getById(material.getCategoryId());
        String categoryType = category == null ? null : category.getCategoryType();
        return org.jeecg.modules.wms.util.WmsInboundRouting.toWarehouseType(categoryType);
    }
}
