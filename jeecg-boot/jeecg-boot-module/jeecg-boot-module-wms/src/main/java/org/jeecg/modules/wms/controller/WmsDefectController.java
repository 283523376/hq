package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDefectInboundDTO;
import org.jeecg.modules.wms.dto.WmsDefectReturnDTO;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.service.IWmsDefectService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsDefectLedgerVO;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 不良品处置（XT-001）——废品仓台账与报废出库。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Tag(name = "WMS-不良品处置")
@RestController
@RequestMapping("/wms/defect")
@Slf4j
public class WmsDefectController {

    private final IWmsDefectService defectService;

    public WmsDefectController(IWmsDefectService defectService) {
        this.defectService = defectService;
    }

    @Operation(summary = "不良品台账-分页（只含废品仓）")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:defect:list")
    public Result<IPage<WmsDefectLedgerVO>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "materialKeyword", required = false) String materialKeyword,
            @RequestParam(name = "batchNo", required = false) String batchNo,
            @RequestParam(name = "locationCode", required = false) String locationCode,
            @RequestParam(name = "ownershipType", required = false) String ownershipType,
            @RequestParam(name = "consignorId", required = false) String consignorId) {
        return Result.OK(defectService.pageLedger(pageNo, pageSize, materialKeyword, batchNo,
                locationCode, ownershipType, consignorId));
    }

    @Operation(summary = "不良品-报废出库")
    @PostMapping(value = "/scrap")
    @RequiresPermissions("wms:defect:scrap")
    @WmsAudit(bizType = "出库单", action = "作废", bizNoSpEL = "#dto.inventoryId")
    public Result<WmsScrapResultVO> scrap(@RequestBody WmsScrapDTO dto) {
        try {
            return Result.OK("报废成功", defectService.scrap(dto));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "不良品-入库登记（生产/受托两类来源，RK-005）")
    @PostMapping(value = "/inbound")
    @RequiresPermissions("wms:defect:inbound")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_DEFECT_RECORD, action = WmsConstants.AUDIT_ACTION_RECEIVE,
            bizNoSpEL = "#dto.sourceBillNo")
    public Result<Void> recordInbound(@RequestBody WmsDefectInboundDTO dto) {
        try {
            defectService.recordInbound(dto, WmsUtils.currentUsername());
            return Result.OK("登记成功", null);
        } catch (RuntimeException e) {
            log.warn("不良品登记失败：{}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "不良品-受托退货（生成受托加工材料退料单）")
    @PostMapping(value = "/return")
    @RequiresPermissions("wms:defect:return")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_DEFECT_RECORD, action = WmsConstants.AUDIT_ACTION_RETURN,
            bizNoSpEL = "#dto.recordId")
    public Result<WmsDefectRecord> returnToConsignor(@RequestBody WmsDefectReturnDTO dto) {
        try {
            return Result.OK("退货成功", defectService.returnToConsignor(dto.getRecordId(),
                    WmsUtils.currentUsername()));
        } catch (RuntimeException e) {
            log.warn("受托退货失败：{}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 不良品记录分页。
     *
     * <p>查询窗口的文本条件由服务层 {@code listPage} 统一手写 {@code like}——
     * 物料编码/来源单号都支持任意连续子串，不要在这里改回等值匹配。
     */
    @Operation(summary = "不良品-记录分页")
    @GetMapping(value = "/records")
    @RequiresPermissions("wms:defect:list")
    public Result<IPage<WmsDefectRecord>> records(
            WmsDefectRecord query,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        return Result.OK(defectService.listPage(pageNo, pageSize, query));
    }
}
