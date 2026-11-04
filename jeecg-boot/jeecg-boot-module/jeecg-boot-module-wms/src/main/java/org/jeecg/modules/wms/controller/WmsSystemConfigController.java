package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.aspect.annotation.AutoLog;
import org.jeecg.common.system.base.controller.JeecgController;
import org.jeecg.modules.wms.constant.WmsConfigGroup;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsConfigConflictVO;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.jeecg.modules.wms.vo.WmsKingdeeReadinessVO;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 金蝶配置与凭据：就绪度自检用；不在模块内时（裁剪部署）不阻塞其他配置读写。 */
    @Autowired(required = false)
    private WmsKingdeeProperties kingdeeProperties;

    @Autowired(required = false)
    private IWmsKingdeeAuthService kingdeeAuthService;

    /** 页面首载：分组结构 + 全部组的值（一次拿全，左菜单与表单都由它渲染）。 */
    @Operation(summary = "系统配置-分组与全部配置项")
    @GetMapping(value = "/definitions")
    public Result<List<WmsConfigGroupVO>> definitions() {
        return Result.OK(service.definitions());
    }

    /**
     * 金蝶对接就绪度自检（V2.4 RK-001 前置）。
     *
     * <p>把「填完配置到底生效没有」变成一次查询。最常见两种误配在这里直接暴露：
     * ① 只填了 5 个 formId 但 `wms.sync.erp.kingdee.enabled` 仍为 false（环境变量没设）；
     * ② 测试环境走 HTTP 却没设 `allow-http=true`，登录会被安全检查拒绝。
     *
     * <p>只返回脱敏后的 appId 与各凭据的「是否已设置」，<b>不回显任何密钥原文</b>。
     */
    @Operation(summary = "系统配置-金蝶对接就绪度")
    @GetMapping(value = "/kingdee-readiness")
    public Result<WmsKingdeeReadinessVO> kingdeeReadiness() {
        WmsKingdeeReadinessVO vo = new WmsKingdeeReadinessVO();
        if (kingdeeProperties == null) {
            // 裁剪部署（金蝶组件未装配）时不能让自检 500：如实报告未就绪
            vo.setTransport("未知");
            vo.setAppIdMasked("****");
            vo.getProblems().add("金蝶配置组件未装配（WmsKingdeeProperties 缺失）");
            vo.setReady(false);
            return Result.OK(vo);
        }
        vo.setEnabled(kingdeeProperties.isEnabled());
        String baseUrl = kingdeeProperties.getBaseUrl() == null ? "" : kingdeeProperties.getBaseUrl().trim();
        vo.setBaseUrl(baseUrl);
        vo.setAcctIdConfigured(isNotBlank(kingdeeProperties.getAcctId()));
        vo.setUsernameConfigured(isNotBlank(kingdeeProperties.getUsername()));
        vo.setAppSecretConfigured(isNotBlank(kingdeeProperties.getAppSecret()));
        vo.setAllowHttp(kingdeeProperties.isAllowHttp());
        vo.setTransport(baseUrl.toLowerCase().startsWith("https://") ? "https"
                : baseUrl.toLowerCase().startsWith("http://") ? "http" : "未知");
        if (kingdeeAuthService != null) {
            vo.setAppIdMasked(kingdeeAuthService.maskedAppId());
            vo.setCredentialsConfigured(kingdeeAuthService.isConfigured());
        } else {
            vo.setAppIdMasked("****");
            vo.setCredentialsConfigured(false);
        }

        for (WmsConfigKey key : WmsConfigKey.values()) {
            if (key.getGroup() != WmsConfigGroup.KINGDEE) {
                continue;
            }
            vo.getFormIds().add(new WmsKingdeeReadinessVO.FormId(
                    key.getKey(), key.getLabel(), service.getString(key.getKey(), "")));
        }

        List<String> problems = vo.getProblems();
        if (!vo.isEnabled()) {
            problems.add("未启用金蝶同步：环境变量 KINGDEE_ERP_ENABLED 需设为 true（对应 "
                    + "wms.sync.erp.kingdee.enabled），改完要重启后端");
        }
        if (!vo.isCredentialsConfigured()) {
            problems.add("金蝶凭据不全：需设置 KINGDEE_ERP_BASE_URL / KINGDEE_ERP_ACCT_ID / "
                    + "KINGDEE_ERP_USERNAME / KINGDEE_ERP_APP_ID / KINGDEE_ERP_APP_SECRET "
                    + "五项环境变量，改完要重启后端");
        }
        if ("http".equals(vo.getTransport()) && !vo.isAllowHttp()) {
            problems.add("目标是 HTTP 但未允许：需设置 KINGDEE_ERP_ALLOW_HTTP=true（测试环境专用，"
                    + "生产必须 HTTPS），改完要重启后端");
        }
        for (WmsKingdeeReadinessVO.FormId formId : vo.getFormIds()) {
            if (!formId.isConfigured()) {
                problems.add(formId.getLabel() + " 未配置 formId（系统配置键 " + formId.getKey()
                        + "）——填完立即生效，无需重启");
            }
        }
        vo.setReady(problems.isEmpty());
        return Result.OK(vo);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
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
    @RequiresPermissions("wms:systemConfig:save")
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
    @RequiresPermissions("wms:systemConfig:rollback")
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
