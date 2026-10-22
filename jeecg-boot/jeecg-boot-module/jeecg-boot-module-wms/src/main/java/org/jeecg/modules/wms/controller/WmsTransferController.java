package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsTransferCreateDTO;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.service.IWmsTransferService;
import org.jeecg.modules.wms.vo.WmsTransferVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * @Description: 库存调拨（CK-004）。
 *
 * <p>双时钟：{@code execStatus} 由 WMS/设备推进，{@code syncStatus} 由金蝶审批推进，
 * 总状态 {@code status} 由后端派生；前端只按 {@code status} 展示。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Tag(name = "WMS-库存调拨")
@RestController
@RequestMapping("/wms/transfer")
@Slf4j
public class WmsTransferController extends JeecgController<WmsTransferOrder, IWmsTransferService> {

    @Operation(summary = "调拨-分页列表")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:transfer:list")
    public Result<IPage<WmsTransferOrder>> list(WmsTransferOrder query,
                                                @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                                @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
                                                @RequestParam(name = "keyword", required = false) String keyword,
                                                @RequestParam(name = "status", required = false) String status,
                                                @RequestParam(name = "transferMode", required = false) String transferMode) {
        LambdaQueryWrapper<WmsTransferOrder> wrapper = new LambdaQueryWrapper<>();
        if (oConvertUtils.isNotEmpty(status)) {
            wrapper.eq(WmsTransferOrder::getStatus, status);
        }
        if (oConvertUtils.isNotEmpty(transferMode)) {
            wrapper.eq(WmsTransferOrder::getTransferMode, transferMode);
        }
        if (oConvertUtils.isNotEmpty(keyword)) {
            wrapper.and(w -> w.like(WmsTransferOrder::getTransferNo, keyword)
                    .or().like(WmsTransferOrder::getErpBillNo, keyword)
                    .or().like(WmsTransferOrder::getOutWarehouseCode, keyword)
                    .or().like(WmsTransferOrder::getInWarehouseCode, keyword));
        }
        wrapper.orderByDesc(WmsTransferOrder::getCreateTime);
        return Result.OK(service.page(new Page<>(pageNo, pageSize), wrapper));
    }

    @Operation(summary = "调拨-详情（含明细/任务/ERP等待时长）")
    @GetMapping(value = "/{id}")
    @RequiresPermissions("wms:transfer:list")
    public Result<WmsTransferVO> detail(@PathVariable("id") String id) {
        try {
            return Result.OK(service.detail(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-新建")
    @PostMapping
    @RequiresPermissions("wms:transfer:add")
    public Result<WmsTransferOrder> create(@RequestBody WmsTransferCreateDTO dto) {
        try {
            return Result.OK("创建成功", service.create(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-分配并下发（同仓走米塔）")
    @PostMapping(value = "/{id}/dispatch")
    @RequiresPermissions("wms:transfer:dispatch")
    public Result<WmsTransferOrder> dispatch(@PathVariable("id") String id) {
        try {
            return Result.OK("已下发", service.dispatch(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-跨仓出发确认（→在途）")
    @PostMapping(value = "/{id}/depart")
    @RequiresPermissions("wms:transfer:execute")
    public Result<WmsTransferOrder> depart(@PathVariable("id") String id) {
        try {
            return Result.OK("已出发", service.depart(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-跨仓到达确认（→待入库，落暂存库位）")
    @PostMapping(value = "/{id}/arrive")
    @RequiresPermissions("wms:transfer:execute")
    public Result<WmsTransferOrder> arrive(@PathVariable("id") String id) {
        try {
            return Result.OK("已到达", service.arrive(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-跨仓上架确认（→实物完成）")
    @PostMapping(value = "/{id}/putaway")
    @RequiresPermissions("wms:transfer:execute")
    public Result<WmsTransferOrder> putaway(@PathVariable("id") String id,
                                            @RequestBody(required = false) Map<String, String> locationByDetailId) {
        try {
            return Result.OK("已上架", service.putaway(id, locationByDetailId));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-提交/刷新金蝶（Phase B）")
    @PostMapping(value = "/{id}/sync-erp")
    @RequiresPermissions("wms:transfer:sync")
    public Result<WmsTransferOrder> syncErp(@PathVariable("id") String id) {
        try {
            return Result.OK("已提交", service.syncErp(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-生成反向冲销单（Phase B）")
    @PostMapping(value = "/{id}/reverse")
    @RequiresPermissions("wms:transfer:reverse")
    public Result<WmsTransferOrder> reverse(@PathVariable("id") String id) {
        try {
            return Result.OK("已生成反向调拨单", service.reverse(id));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "调拨-关单（释放锁定）")
    @PostMapping(value = "/{id}/close")
    @RequiresPermissions("wms:transfer:close")
    public Result<WmsTransferOrder> close(@PathVariable("id") String id,
                                          @RequestParam("reason") String reason) {
        try {
            return Result.OK("已关单", service.close(id, reason));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
