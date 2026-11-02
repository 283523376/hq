package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.service.IWmsDefectService;
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
            @RequestParam(name = "locationCode", required = false) String locationCode) {
        return Result.OK(defectService.pageLedger(pageNo, pageSize, materialKeyword, batchNo, locationCode));
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
}
