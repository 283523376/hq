package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
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
 * @deprecated 方案 A（原材料+焊接线边仓）已移出项目范围；菜单已收回，
 *             保留代码与数据用于历史查询与回滚，不再新增入口。
 */
@Deprecated
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
    // 技术债 §5-18：权限码沿用 V3.9.5_11 的种子 wms:inspection:do。
    // 该模块随方案 A 停用、权限点已逻辑删除，加注解是 fail-closed：
    // 真要用时须先恢复权限点与角色授权，不能靠「没注解」默认放行。
    @RequiresPermissions("wms:inspection:do")
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
