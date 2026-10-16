package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.vo.WmsConfigConflictVO;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Description: 系统配置（蓝图 YX-WMS-BD-004）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Tag(name = "WMS-系统配置")
@RestController
@RequestMapping("/wms/systemConfig")
@Slf4j
public class WmsSystemConfigController extends JeecgController<WmsSystemConfig, IWmsSystemConfigService> {

    /** 页面首载：分组结构 + 全部组的值（一次拿全，左菜单与表单都由它渲染）。 */
    @Operation(summary = "系统配置-分组与全部配置项")
    @GetMapping(value = "/definitions")
    public Result<List<WmsConfigGroupVO>> definitions() {
        return Result.OK(service.definitions());
    }

    /** 取单个分组的值（保存/回滚后局部刷新用）。 */
    @Operation(summary = "系统配置-按分组查询")
    @GetMapping(value = "/list")
    public Result<List<WmsConfigItemVO>> listByGroup(@RequestParam(name = "group") String group) {
        // 必须 trim 后再判空：oConvertUtils.isEmpty 不 trim，纯空白分组会穿过去，
        // 最终抛出「未知配置分组：  」这种原始异常文案，而不是统一的「参数不识别！」
        if (group == null || group.trim().isEmpty()) {
            return Result.error("参数不识别！");
        }
        try {
            return Result.OK(service.listByGroup(group));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /**
     * 保存某分组的全部配置。
     *
     * <p>冲突时 message **逐字**为蓝图原文，明细挂在 result 上：这样「逐字断言」与
     * 「用户能看到具体是哪两项打架」两个要求同时满足。
     */
    @AutoLog(value = "系统配置-保存", operateType = 3)
    @Operation(summary = "系统配置-保存")
    @PostMapping(value = "/save")
    public Result<WmsConfigConflictVO> save(@RequestBody WmsConfigSaveDTO dto) {
        try {
            service.saveGroup(dto);
            return Result.OK("保存成功！");
        } catch (WmsConfigConflictException e) {
            log.warn("系统配置冲突：分组={}，明细={}", dto == null ? null : dto.getGroup(), e.getConflicts());
            return Result.error(e.getMessage(), new WmsConfigConflictVO(e.getConflicts()));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 回滚某分组到上一版本（无可用版本时的提示与「冲突」文案不同）。 */
    @AutoLog(value = "系统配置-回滚", operateType = 3)
    @Operation(summary = "系统配置-回滚")
    @PostMapping(value = "/rollback")
    public Result<WmsConfigConflictVO> rollback(@RequestBody WmsConfigRollbackDTO dto) {
        try {
            service.rollback(dto);
            return Result.OK("回滚成功！");
        } catch (WmsConfigConflictException e) {
            return Result.error(e.getMessage(), new WmsConfigConflictVO(e.getConflicts()));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Result.error(e.getMessage());
        }
    }

    /** 某键的版本流水（让「留痕」可查证）。 */
    @Operation(summary = "系统配置-版本历史")
    @GetMapping(value = "/history")
    public Result<List<WmsSystemConfigHistory>> history(@RequestParam(name = "key") String key) {
        // 同上：trim 后再判空
        if (key == null || key.trim().isEmpty()) {
            return Result.error("参数不识别！");
        }
        return Result.OK(service.history(key));
    }
}
