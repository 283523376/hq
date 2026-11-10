package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsExportTaskService;
import org.jeecg.modules.wms.service.IWmsImpactService;
import org.jeecg.modules.wms.vo.WmsImpactVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 审计留痕查询（XT-002）。
 *
 * <p>**只有查，没有改和删**：审计表不可变，清理只走保留期任务。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Tag(name = "WMS-审计留痕")
@RestController
@RequestMapping("/wms/audit")
@Slf4j
public class WmsAuditController {

    private final IWmsAuditLogService auditLogService;
    private final IWmsImpactService impactService;
    private final IWmsExportTaskService exportTaskService;

    public WmsAuditController(IWmsAuditLogService auditLogService,
                              IWmsImpactService impactService,
                              IWmsExportTaskService exportTaskService) {
        this.auditLogService = auditLogService;
        this.impactService = impactService;
        this.exportTaskService = exportTaskService;
    }

    @Operation(summary = "审计日志-分页列表")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:audit:list")
    public Result<IPage<WmsAuditLog>> list(WmsAuditQueryDTO query,
                                           @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
                                           @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize) {
        if (query == null) {
            query = new WmsAuditQueryDTO();
        }
        query.setPageNo(pageNo);
        query.setPageSize(pageSize);
        return Result.OK(auditLogService.pageQuery(query));
    }

    @Operation(summary = "审计日志-创建异步导出任务")
    @PostMapping(value = "/export")
    @RequiresPermissions("wms:audit:export")
    public Result<String> export(WmsAuditQueryDTO query) {
        String operator = org.jeecg.modules.wms.util.WmsUtils.currentUsername();
        if (oConvertUtils.isEmpty(operator)) {
            return Result.error("未获取到登录用户，无法导出");
        }
        try {
            return Result.OK("导出任务已提交", exportTaskService.createAuditExport(query, operator));
        } catch (Exception e) {
            log.warn("创建审计导出任务失败", e);
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "影响面查询：这张工单在 WMS 碰过什么")
    @GetMapping(value = "/impact")
    @RequiresPermissions("wms:audit:list")
    public Result<WmsImpactVO> impact(@RequestParam(name = "workOrderNo") String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo) || workOrderNo.trim().isEmpty()) {
            return Result.error("工单号不能为空");
        }
        try {
            return Result.OK(impactService.analyzeByWorkOrderNo(workOrderNo.trim()));
        } catch (Exception e) {
            log.warn("影响面查询失败：workOrderNo={}", workOrderNo, e);
            return Result.error(e.getMessage());
        }
    }
}
