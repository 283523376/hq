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
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService;
import org.jeecg.modules.wms.service.IWmsProductionInboundService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Description: 生产工单查询与 ERP 同步（YX-WMS-BD-002，V2.2：ERP 为唯一业务来源，
 * 手工新建/编辑/删除已下线；MES 推送保留为兼容入口）。
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

    @Autowired
    private IWmsProductionInboundService productionInboundService;

    @Operation(summary = "生产工单-分页列表")
    @GetMapping(value = "/list")
    public Result<IPage<WmsWorkOrder>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "workOrderNo", required = false) String workOrderNo,
            @RequestParam(name = "productType", required = false) String productType,
            @RequestParam(name = "workOrderStatus", required = false) String workOrderStatus,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "syncStatus", required = false) String syncStatus,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return Result.OK(service.listPage(pageNo, pageSize, workOrderNo, productType, workOrderStatus,
                source, syncStatus, startDate, endDate, keyword));
    }

    @RequiresPermissions("wms:work-order:sync")
    @AutoLog(value = "生产工单-ERP同步", operateType = 3)
    @Operation(summary = "生产工单-从金蝶 PRD_MO 同步（唯一业务写入口）")
    @PostMapping(value = "/sync-kingdee")
    public Result<WmsKingdeeWorkOrderSyncResultVO> syncKingdee(
            @RequestParam(name = "billNo", required = false) String billNo,
            @RequestParam(name = "startDate", required = false) String startDate,
            @RequestParam(name = "endDate", required = false) String endDate,
            @RequestParam(name = "top", required = false) Integer top) {
        try {
            return Result.OK("同步完成", kingdeeSyncService.syncFromKingdee(billNo, startDate, endDate, top));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 工单关联的生产入库记录（蓝图：查询工单执行情况，展示工单关联的入库记录）。
     */
    @Operation(summary = "生产工单-关联入库记录")
    @GetMapping(value = "/inbound-records")
    public Result<List<WmsProductionInbound>> inboundRecords(
            @RequestParam(name = "workOrderNo", required = false) String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            return Result.error("工单号不能为空");
        }
        return Result.OK(productionInboundService.listByWorkOrderNo(workOrderNo.trim()));
    }

    /**
     * MES 推送工单。外部系统无法持有用户 JWT，使用共享令牌鉴权。
     * 兼容入口（BD-002 V2.2 保留）：不得覆盖 ERP 来源、不得重开已关闭工单。
     */
    @IgnoreAuth
    @Operation(summary = "生产工单-MES推送（外部系统；令牌不允许进系统日志，故不加 @AutoLog）")
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
