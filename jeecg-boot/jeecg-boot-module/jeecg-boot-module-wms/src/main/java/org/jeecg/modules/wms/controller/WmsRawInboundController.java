package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsPutawayDTO;
import org.jeecg.modules.wms.dto.WmsRawInboundDTO;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.service.IWmsRawInboundService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsRawPutawayResultVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 外购/受托原材料入库（RK-001 V2.4，spec §3.2、§5）。
 *
 * <p>与 {@code WmsInboundController}（采购入库检验链路，已停用）的区别：<strong>跳过检验</strong>。
 * 到货登记后直接「待分配」，上架时一步写库存 + 置「已审核」并回写金蝶。
 *
 * <p>旧链路 {@code /wms/inbound/{id}/putaway} 只写自有库存行，受托货会串账，
 * 因此 {@code WmsInboundController} 对 {@code sourceType} 非空的单据显式拒绝，统一走本控制器。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Tag(name = "WMS-原材料入库")
@RestController
@RequestMapping("/wms/raw-inbound")
@Slf4j
public class WmsRawInboundController {

    private final IWmsRawInboundService rawInboundService;

    public WmsRawInboundController(IWmsRawInboundService rawInboundService) {
        this.rawInboundService = rawInboundService;
    }

    @Operation(summary = "原材料入库-分页（只含外购/受托通道的单据）")
    @GetMapping(value = "/page")
    @RequiresPermissions("wms:inbound:consigned")
    public Result<IPage<WmsInboundOrder>> page(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "inboundStatus", required = false) String inboundStatus,
            @RequestParam(name = "orderNo", required = false) String orderNo,
            @RequestParam(name = "supplierName", required = false) String supplierName) {
        return Result.OK(rawInboundService.listPage(pageNo, pageSize, sourceType, inboundStatus,
                orderNo, supplierName));
    }

    @Operation(summary = "原材料入库-到货登记（外购/受托 × 段差棒直入/大改小）")
    @PostMapping(value = "/receive")
    @RequiresPermissions("wms:inbound:consigned")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_INBOUND, action = WmsConstants.AUDIT_ACTION_CREATE,
            bizNoSpEL = "#dto.erpOrderNo")
    public Result<WmsInboundOrder> receive(@RequestBody WmsRawInboundDTO dto) {
        try {
            return Result.OK("登记成功", rawInboundService.receive(dto, WmsUtils.currentUsername()));
        } catch (RuntimeException e) {
            log.warn("原材料到货登记失败：{}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "原材料入库-上架（段差棒直入/受托货上架并回写金蝶）")
    @PostMapping(value = "/{id}/putaway")
    @RequiresPermissions("wms:inbound:consigned")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_INBOUND, action = WmsConstants.AUDIT_ACTION_PUTAWAY,
            bizNoSpEL = "#id")
    public Result<WmsRawPutawayResultVO> putaway(@PathVariable("id") String id, @RequestBody WmsPutawayDTO dto) {
        try {
            // 回写失败不回滚库存，结果对象带同步状态返回，由前端如实提示
            return Result.OK("上架成功", rawInboundService.putawayRaw(id, dto, WmsUtils.currentUsername()));
        } catch (RuntimeException e) {
            log.warn("原材料上架失败：id={}，{}", id, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "原材料入库-重试回写金蝶（补偿入口，失败单据用）")
    @PostMapping(value = "/{id}/resync")
    @RequiresPermissions("wms:inbound:consigned")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_INBOUND, action = WmsConstants.AUDIT_ACTION_WRITE_BACK,
            bizNoSpEL = "#id")
    public Result<WmsRawPutawayResultVO> resync(@PathVariable("id") String id) {
        try {
            WmsRawPutawayResultVO result = rawInboundService.resync(id, WmsUtils.currentUsername());
            // 回写失败不算接口失败（库存/单据状态不受影响），但 message 不能谎报成功
            String message = WmsConstants.SYNC_STATUS_SUCCESS.equals(result.getSyncStatus())
                    ? "回写成功" : "回写失败";
            return Result.OK(message, result);
        } catch (RuntimeException e) {
            log.warn("原材料重试回写失败：id={}，{}", id, e.getMessage());
            return Result.error(e.getMessage());
        }
    }
}
