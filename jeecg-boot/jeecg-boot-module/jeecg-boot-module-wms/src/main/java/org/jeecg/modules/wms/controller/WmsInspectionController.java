package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.dto.WmsInspectCompleteDTO;
import org.jeecg.modules.wms.service.IWmsInspectionService;
import org.jeecg.modules.wms.vo.WmsInspectionTaskVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 来料检验任务（RK-001）。
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Tag(name = "WMS-来料检验")
@RestController
@RequestMapping("/wms/inspection")
@Slf4j
public class WmsInspectionController {

    @Autowired
    private IWmsInspectionService inspectionService;

    @Operation(summary = "检验-分页列表")
    @GetMapping(value = "/list")
    public Result<IPage<WmsInspectionTaskVO>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "inspectStatus", required = false) String inspectStatus) {
        return Result.OK(inspectionService.listPage(pageNo, pageSize, inspectStatus));
    }

    @Operation(summary = "检验-提交结果（合格/不合格）")
    @PostMapping(value = "/{id}/complete")
    public Result<Void> complete(@PathVariable("id") String id, @RequestBody WmsInspectCompleteDTO dto) {
        try {
            inspectionService.complete(id, dto.getResult(), dto.getRemark());
            return Result.OK("检验结果已提交", null);
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
