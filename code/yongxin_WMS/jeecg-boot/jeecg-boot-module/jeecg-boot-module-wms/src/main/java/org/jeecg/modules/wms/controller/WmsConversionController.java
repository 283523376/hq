package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsConversionCreateDTO;
import org.jeecg.modules.wms.entity.WmsConversionOrder;
import org.jeecg.modules.wms.service.IWmsConversionService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 改制单（大改小，V2.4 spec §3.1、§5）。
 *
 * <p>投入原材料不入 WMS 库存（登记即台账）；完成时生成生产入库单，产出按自有库存入账。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Tag(name = "WMS-改制单")
@RestController
@RequestMapping("/wms/conversion")
@Slf4j
public class WmsConversionController {

    private final IWmsConversionService conversionService;

    public WmsConversionController(IWmsConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Operation(summary = "改制单-建单（关联生产订单）")
    @PostMapping
    @RequiresPermissions("wms:conversion:create")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_CONVERSION, action = WmsConstants.AUDIT_ACTION_CREATE,
            bizNoSpEL = "#dto.productionOrderNo")
    public Result<WmsConversionOrder> create(@RequestBody WmsConversionCreateDTO dto) {
        try {
            return Result.OK("建单成功", conversionService.create(dto, WmsUtils.currentUsername()));
        } catch (RuntimeException e) {
            log.warn("改制单建单失败：{}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "改制单-完成（生成生产入库单）")
    @PostMapping(value = "/{id}/complete")
    @RequiresPermissions("wms:conversion:execute")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_CONVERSION, action = WmsConstants.AUDIT_ACTION_COMPLETE,
            bizNoSpEL = "#id")
    public Result<Void> complete(@PathVariable("id") String id) {
        try {
            conversionService.complete(id, WmsUtils.currentUsername());
            return Result.OK("完成改制", null);
        } catch (RuntimeException e) {
            log.warn("改制单完成失败：id={}，{}", id, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "改制单-取消")
    @PostMapping(value = "/{id}/cancel")
    @RequiresPermissions("wms:conversion:cancel")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_CONVERSION, action = WmsConstants.AUDIT_ACTION_CANCEL_BILL,
            bizNoSpEL = "#id")
    public Result<Void> cancel(@PathVariable("id") String id) {
        try {
            conversionService.cancel(id, WmsUtils.currentUsername());
            return Result.OK("已取消", null);
        } catch (RuntimeException e) {
            log.warn("改制单取消失败：id={}，{}", id, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "改制单-分页列表")
    @GetMapping(value = "/page")
    @RequiresPermissions("wms:conversion:view")
    public Result<IPage<WmsConversionOrder>> page(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "conversionNo", required = false) String conversionNo,
            @RequestParam(name = "productionOrderNo", required = false) String productionOrderNo,
            @RequestParam(name = "status", required = false) String status) {
        return Result.OK(conversionService.listPage(pageNo, pageSize, conversionNo, productionOrderNo, status));
    }
}
