package org.jeecg.modules.wms.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 写操作端点必须声明权限码。
 *
 * <p>背景：这些控制器的按钮在前端有 {@code v-auth} 隐藏，但后端一致没加
 * {@code @RequiresPermissions}——任何人只要知道 URL 就能绕过 UI 直接调用
 * （删除物料、改库位属性、保存系统配置……）。前端隐藏不是权限。
 *
 * <p>本测试用反射做静态扫描，防止以后新增端点时又漏掉。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
class WmsControllerPermissionsTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            WmsMaterialController.class,
            WmsMaterialCategoryController.class,
            WmsSupplierController.class,
            WmsWarehouseController.class,
            WmsLocationController.class,
            WmsDeviceController.class,
            WmsOrderController.class,
            WmsInboundController.class,
            WmsSystemConfigController.class);

    /**
     * 机器回调端点：调用方是 WCS/RMS 而非登录用户，加权限注解会让上游回传全部 401。
     */
    private static final Set<String> MACHINE_ENDPOINTS = Set.of("WmsDeviceController#reportStatus");

    @Test
    void writeEndpointsShouldDeclarePermission() {
        List<String> missing = new ArrayList<>();
        for (Class<?> clazz : CONTROLLERS) {
            for (Method method : clazz.getDeclaredMethods()) {
                String key = clazz.getSimpleName() + "#" + method.getName();
                if (method.isSynthetic() || MACHINE_ENDPOINTS.contains(key)) {
                    continue;
                }
                if (isWriteEndpoint(method) && !method.isAnnotationPresent(RequiresPermissions.class)) {
                    missing.add(key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "以下写操作端点缺少 @RequiresPermissions：" + missing);
    }

    /** 变更类端点：POST / PUT / DELETE（含 {@code @RequestMapping} 显式声明这些 method 的写法）。 */
    private boolean isWriteEndpoint(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)) {
            return true;
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        return Arrays.stream(mapping.method()).anyMatch(m ->
                m == RequestMethod.POST || m == RequestMethod.PUT || m == RequestMethod.DELETE);
    }
}
