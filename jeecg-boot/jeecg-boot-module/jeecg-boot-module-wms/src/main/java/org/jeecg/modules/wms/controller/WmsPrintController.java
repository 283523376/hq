package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.wms.audit.WmsAudit;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.IWmsPrintService;
import org.jeecg.modules.wms.vo.WmsPrintBillListVO;
import org.jeecg.modules.wms.vo.WmsPrintDataVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @Description: 单据打印（DY-001）——7 类定稿单据的打印数据与本地单号下拉。
 *
 * <p>本模块**只读**：数据来自 WMS 本地单据或金蝶 WebAPI 查询，绝不写金蝶。
 * 查询端点带 {@code @WmsAudit} 留痕（XT-002 口径：谁在什么时候打了哪张单）。
 * 业务失败文案由 service 给出、此处原样透出（不依赖全局处理器加前缀）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-09
 */
@Tag(name = "WMS-单据打印")
@RestController
@RequestMapping("/wms/print")
@Slf4j
public class WmsPrintController {

    private final IWmsPrintService printService;

    public WmsPrintController(IWmsPrintService printService) {
        this.printService = printService;
    }

    @Operation(summary = "单据打印-取打印数据（分页已由后端切好）")
    @GetMapping(value = "/data")
    @RequiresPermissions("wms:print:list")
    @WmsAudit(bizType = WmsConstants.AUDIT_BIZ_PRINT, action = WmsConstants.AUDIT_ACTION_QUERY,
            bizNoSpEL = "#billNo")
    public Result<WmsPrintDataVO> data(
            @RequestParam(name = "billType") String billType,
            @RequestParam(name = "billNo") String billNo) {
        try {
            return Result.OK(printService.printData(billType, billNo));
        } catch (RuntimeException e) {
            log.warn("单据打印取数失败：类型={}，单号={}，原因={}", billType, billNo, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "单据打印-本地单号下拉（盘盈亏/销售出库）")
    @GetMapping(value = "/local-bills")
    @RequiresPermissions("wms:print:list")
    public Result<List<Map<String, String>>> localBills(
            @RequestParam(name = "billType") String billType,
            @RequestParam(name = "keyword", required = false) String keyword) {
        try {
            return Result.OK(printService.localBills(billType, keyword));
        } catch (RuntimeException e) {
            log.warn("单据打印下拉失败：类型={}，原因={}", billType, e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "单据打印-工作台单据列表（默认全部可打印，单号模糊，每页 20 条）")
    @GetMapping(value = "/bills")
    @RequiresPermissions("wms:print:list")
    public Result<WmsPrintBillListVO> bills(
            @RequestParam(name = "billType") String billType,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "period", required = false) String period,
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "20") Integer pageSize) {
        try {
            return Result.OK(printService.bills(billType, keyword, period, pageNo, pageSize));
        } catch (RuntimeException e) {
            log.warn("单据打印列表失败：类型={}，原因={}", billType, e.getMessage());
            return Result.error(e.getMessage());
        }
    }
}
