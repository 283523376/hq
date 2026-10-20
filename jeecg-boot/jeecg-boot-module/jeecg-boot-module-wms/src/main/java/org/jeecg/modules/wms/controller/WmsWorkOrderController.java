package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 生产工单维护与 MES 推送（YX-WMS-RK-002）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-生产工单")
@RestController
@RequestMapping("/wms/work-order")
@Slf4j
public class WmsWorkOrderController extends JeecgController<WmsWorkOrder, IWmsWorkOrderService> {

    @Value("${wms.sync.mes.push-token:}")
    private String pushToken;

    @Autowired
    private IWmsKingdeeWorkOrderSyncService kingdeeSyncService;

    @Operation(summary = "生产工单-分页列表")
    @GetMapping(value = "/list")
    public Result<IPage<WmsWorkOrder>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "workOrderNo", required = false) String workOrderNo,
            @RequestParam(name = "productType", required = false) String productType,
            @RequestParam(name = "workOrderStatus", required = false) String workOrderStatus) {
        return Result.OK(service.listPage(pageNo, pageSize, workOrderNo, productType, workOrderStatus));
    }

    @RequiresPermissions("wms:work-order:edit")
    @Operation(summary = "生产工单-从金蝶 PRD_MO 同步")
    @PostMapping(value = "/sync-kingdee")
    public Result<WmsKingdeeWorkOrderSyncResultVO> syncKingdee(
            @RequestParam(name = "billNo", required = false) String billNo,
            @RequestParam(name = "top", defaultValue = "50") Integer top) {
        try {
            return Result.OK("同步完成", kingdeeSyncService.syncFromKingdee(billNo, top));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:work-order:add")
    @AutoLog(value = "生产工单-新增", operateType = 2)
    @Operation(summary = "生产工单-新增")
    @PostMapping(value = "/add")
    public Result<WmsWorkOrder> add(@RequestBody WmsWorkOrder order) {
        try {
            service.addWorkOrder(order);
            return Result.OK("新增成功", order);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:work-order:edit")
    @AutoLog(value = "生产工单-编辑", operateType = 3)
    @Operation(summary = "生产工单-编辑")
    @PutMapping(value = "/edit")
    public Result<WmsWorkOrder> edit(@RequestBody WmsWorkOrder order) {
        try {
            service.editWorkOrder(order);
            return Result.OK("编辑成功", order);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @RequiresPermissions("wms:work-order:delete")
    @AutoLog(value = "生产工单-删除", operateType = 4)
    @Operation(summary = "生产工单-删除")
    @DeleteMapping(value = "/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        try {
            service.removeWorkOrder(id);
            return Result.OK("删除成功", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * MES 推送工单。外部系统无法持有用户 JWT，使用共享令牌鉴权。
     */
    @IgnoreAuth
    @Operation(summary = "生产工单-MES推送（外部系统）")
    @PostMapping(value = "/push")
    public Result<WmsWorkOrder> push(@RequestHeader(name = "X-MES-Token", required = false) String token,
                                     @RequestBody WmsWorkOrderPushDTO dto) {
        if (!tokenValid(token)) {
            return Result.error("MES 推送令牌无效");
        }
        try {
            return Result.OK("推送成功", service.push(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    private boolean tokenValid(String token) {
        return oConvertUtils.isNotEmpty(pushToken) && pushToken.equals(token);
    }
}
