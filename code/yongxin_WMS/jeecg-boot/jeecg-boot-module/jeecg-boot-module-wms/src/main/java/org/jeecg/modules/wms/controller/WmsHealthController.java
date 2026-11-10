package org.jeecg.modules.wms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WMS 模块初始化检查接口。
 *
 * <p>仅用于确认模块已进入运行时 classpath 并示范统一的 {@link Result} 返回规范，
 * 不含任何业务逻辑与数据库访问。
 */
@Slf4j
@Tag(name = "WMS-初始化检查")
@RestController
@RequestMapping("/wms/health")
public class WmsHealthController {

    /**
     * 连通性检查。
     *
     * <p>标注 {@link IgnoreAuth}（该注解仅支持方法级），因此可在未登录状态下用于冒烟测试。
     *
     * @return 固定成功响应
     */
    @IgnoreAuth
    @Operation(summary = "WMS 模块连通性检查")
    @GetMapping("/ping")
    public Result<String> ping() {
        log.info("[{}] 健康检查被调用", WmsConstants.MODULE_NAME);
        return Result.OK("WMS module ready");
    }
}
