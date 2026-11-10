package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsWarningHandleDTO;
import org.jeecg.modules.wms.entity.WmsInventoryWarning;
import org.jeecg.modules.wms.mapper.WmsInventoryWarningMapper;
import org.jeecg.modules.wms.service.IWmsInventoryWarningService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsWarningScanResultVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 库存预警单（KC-001）。
 *
 * <p>列表/统计/处理/手动扫描；定时扫描见 {@code WmsInventoryWarningScanJob}。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Tag(name = "WMS-库存预警")
@RestController
@RequestMapping("/wms/inventoryWarning")
@Slf4j
public class WmsInventoryWarningController {

    private final IWmsInventoryWarningService warningService;
    private final WmsInventoryWarningMapper warningMapper;

    public WmsInventoryWarningController(IWmsInventoryWarningService warningService,
                                         WmsInventoryWarningMapper warningMapper) {
        this.warningService = warningService;
        this.warningMapper = warningMapper;
    }

    @Operation(summary = "预警-分页列表")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:warning:list")
    public Result<IPage<WmsInventoryWarning>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "ruleType", required = false) String ruleType,
            @RequestParam(name = "materialCode", required = false) String materialCode,
            @RequestParam(name = "batchNo", required = false) String batchNo) {
        LambdaQueryWrapper<WmsInventoryWarning> wrapper = new LambdaQueryWrapper<WmsInventoryWarning>()
                .eq(status != null && !status.isEmpty(), WmsInventoryWarning::getStatus, status)
                .eq(ruleType != null && !ruleType.isEmpty(), WmsInventoryWarning::getRuleType, ruleType)
                .like(materialCode != null && !materialCode.isEmpty(),
                        WmsInventoryWarning::getMaterialCode, materialCode)
                .eq(batchNo != null && !batchNo.isEmpty(), WmsInventoryWarning::getBatchNo, batchNo)
                .orderByDesc(WmsInventoryWarning::getLastHitTime);
        return Result.OK(warningMapper.selectPage(new Page<>(pageNo, pageSize), wrapper));
    }

    @Operation(summary = "预警-待处理数量（顶栏红点）")
    @GetMapping(value = "/pendingCount")
    @RequiresPermissions("wms:warning:list")
    public Result<Long> pendingCount() {
        Long count = warningMapper.selectCount(new LambdaQueryWrapper<WmsInventoryWarning>()
                .eq(WmsInventoryWarning::getStatus, WmsConstants.WARNING_STATUS_PENDING));
        return Result.OK(count == null ? 0L : count);
    }

    @AutoLog(value = "预警-处理", operateType = 3)
    @Operation(summary = "预警-处理并解除（记录处理方式与说明，不改库存）")
    @PostMapping(value = "/handle")
    @RequiresPermissions("wms:warning:handle")
    public Result<String> handle(@RequestBody WmsWarningHandleDTO dto) {
        try {
            warningService.handle(dto, WmsUtils.currentRealname());
            return Result.OK("处理成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "预警-立即扫描", operateType = 1)
    @Operation(summary = "预警-立即扫描（与定时任务同一套逻辑）")
    @PostMapping(value = "/scan")
    @RequiresPermissions("wms:warning:scan")
    public Result<WmsWarningScanResultVO> scan() {
        try {
            return Result.OK("扫描完成", warningService.scan());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
