package org.jeecg.modules.wms.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 写操作端点必须声明权限码。
 *
 * <p>背景：这些控制器的按钮在前端有 {@code v-auth} 隐藏，但后端一致没加
 * {@code @RequiresPermissions}——任何人只要知道 URL 就能绕过 UI 直接调用
 * （删除物料、改库位属性、保存系统配置……）。前端隐藏不是权限。
 *
 * <p>2026-11-10（技术债 §5-18）：原实现是手工维护控制器清单，新增控制器忘了登记就不会被检查。
 * 改为按包扫描 {@code org.jeecg.modules.wms.controller} 下所有控制器，清单只作为
 * 「扫描没坏」的兜底断言。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
class WmsControllerPermissionsTest {

    private static final String CONTROLLER_PACKAGE = "org.jeecg.modules.wms.controller";

    /**
     * 机器回调端点：调用方是 WCS/RMS/MES 而非登录用户，加权限注解会让上游回传全部 401。
     * 2026-11-10 包扫描后补登记（技术债 §5-18 暴露的漏网端点）。
     */
    private static final Set<String> MACHINE_ENDPOINTS = Set.of(
            "WmsDeviceController#reportStatus",
            "WmsRmsCallbackController#taskFeedback",
            "WmsRmsCallbackController#containerLocation",
            "WmsWcsCallbackController#taskFeedback",
            "WmsWorkOrderController#push");

    /** 扫描必须命中的核心控制器——防止扫描配置写错后测试「空跑通过」。 */
    private static final Set<String> MUST_BE_DISCOVERED = Set.of(
            "WmsMaterialController", "WmsMaterialCategoryController", "WmsMaterialGroupMapController",
            "WmsSupplierController", "WmsCustomerController", "WmsWarehouseController",
            "WmsLocationController", "WmsDeviceController", "WmsOrderController",
            "WmsInboundController", "WmsSystemConfigController", "WmsWarningRuleController",
            "WmsInventoryWarningController", "WmsBatchTraceController", "WmsSyncController");

    private final List<Class<?>> controllers = discoverControllers();

    @Test
    void writeEndpointsShouldDeclarePermission() {
        List<String> missing = new ArrayList<>();
        for (Class<?> clazz : controllers) {
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

    @Test
    void scannerShouldDiscoverAllControllers() {
        Set<String> discovered = new LinkedHashSet<>();
        for (Class<?> clazz : controllers) {
            discovered.add(clazz.getSimpleName());
        }
        assertFalse(discovered.isEmpty(), "包扫描没发现任何控制器，扫描配置可能已失效");
        for (String required : MUST_BE_DISCOVERED) {
            assertTrue(discovered.contains(required),
                    "控制器 " + required + " 未被扫描到（新增控制器时也可能踩到这个问题）");
        }
    }

    private static List<Class<?>> discoverControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("控制器类加载失败：" + definition.getBeanClassName(), e);
            }
        }
        return found;
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
