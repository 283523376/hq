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
import org.jeecg.modules.wms.entity.WmsWarningRule;
import org.jeecg.modules.wms.service.IWmsWarningRuleService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description: 库存预警规则（KC-001）。
 *
 * <p>校验失败一律 `try/catch → Result.error` 逐字透出（不依赖全局异常处理器加前缀），
 * 口径见 spec 2026-10-28 第 4 节。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Tag(name = "WMS-库存预警规则")
@RestController
@RequestMapping("/wms/warningRule")
@Slf4j
public class WmsWarningRuleController {

    private final IWmsWarningRuleService ruleService;

    public WmsWarningRuleController(IWmsWarningRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @Operation(summary = "预警规则-分页列表")
    @GetMapping(value = "/list")
    @RequiresPermissions("wms:warning:list")
    public Result<IPage<WmsWarningRule>> list(
            @RequestParam(name = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(name = "ruleType", required = false) String ruleType,
            @RequestParam(name = "enabled", required = false) Integer enabled,
            @RequestParam(name = "keyword", required = false) String keyword) {
        LambdaQueryWrapper<WmsWarningRule> wrapper = new LambdaQueryWrapper<WmsWarningRule>()
                .eq(ruleType != null && !ruleType.isEmpty(), WmsWarningRule::getRuleType, ruleType)
                .eq(enabled != null, WmsWarningRule::getEnabled, enabled)
                .like(keyword != null && !keyword.isEmpty(), WmsWarningRule::getRuleName, keyword)
                .orderByDesc(WmsWarningRule::getCreateTime);
        return Result.OK(ruleService.page(new Page<>(pageNo, pageSize), wrapper));
    }

    @Operation(summary = "预警规则-详情")
    @GetMapping(value = "/queryById")
    @RequiresPermissions("wms:warning:list")
    public Result<WmsWarningRule> queryById(@RequestParam("id") String id) {
        WmsWarningRule rule = ruleService.getById(id);
        return rule == null ? Result.error("规则不存在或已删除") : Result.OK(rule);
    }

    @AutoLog(value = "预警规则-新增", operateType = 2)
    @Operation(summary = "预警规则-新增")
    @PostMapping(value = "/add")
    @RequiresPermissions("wms:warning:add")
    public Result<WmsWarningRule> add(@RequestBody WmsWarningRule rule) {
        try {
            WmsUtils.fillCreate(rule);
            return Result.OK("添加成功", ruleService.createRule(rule));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "预警规则-编辑", operateType = 3)
    @Operation(summary = "预警规则-编辑")
    @PutMapping(value = "/edit")
    @RequiresPermissions("wms:warning:edit")
    public Result<WmsWarningRule> edit(@RequestBody WmsWarningRule rule) {
        try {
            WmsUtils.fillUpdate(rule);
            return Result.OK("编辑成功", ruleService.updateRule(rule));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @AutoLog(value = "预警规则-删除", operateType = 4)
    @Operation(summary = "预警规则-删除")
    @DeleteMapping(value = "/delete")
    @RequiresPermissions("wms:warning:delete")
    public Result<String> delete(@RequestParam("id") String id) {
        try {
            ruleService.removeRule(id);
            return Result.OK("删除成功");
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
