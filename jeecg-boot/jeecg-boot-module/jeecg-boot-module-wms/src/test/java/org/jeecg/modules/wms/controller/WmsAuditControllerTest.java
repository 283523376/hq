package org.jeecg.modules.wms.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsExportTaskService;
import org.jeecg.modules.wms.service.IWmsImpactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 审计查询端点单测。
 *
 * <p>审计日志里什么都有——谁改了配置、谁回写了 ERP、失败原因是什么。
 * 所以查询端点必须是**权限码保护**的，不能只靠前端隐藏菜单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditControllerTest {

    private IWmsAuditLogService auditLogService;
    private IWmsImpactService impactService;
    private IWmsExportTaskService exportTaskService;
    private WmsAuditController controller;

    @BeforeEach
    void setUp() {
        auditLogService = mock(IWmsAuditLogService.class);
        impactService = mock(IWmsImpactService.class);
        exportTaskService = mock(IWmsExportTaskService.class);
        controller = new WmsAuditController(auditLogService, impactService, exportTaskService);
    }

    /** 两个端点都必须挂 wms:audit:list，且是同一枚权限码。 */
    @Test
    void list_requiresPermissionCode() throws Exception {
        java.util.Map<String, String> expected = java.util.Map.of(
                "list", "wms:audit:list", "impact", "wms:audit:list", "export", "wms:audit:export");
        for (String name : new String[]{"list", "impact", "export"}) {
            Method method = null;
            for (Method m : WmsAuditController.class.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    method = m;
                }
            }
            assertNotNull(method, "找不到端点方法：" + name);
            RequiresPermissions annotation = method.getAnnotation(RequiresPermissions.class);
            assertNotNull(annotation, name + " 缺少 @RequiresPermissions");
            assertEquals(expected.get(name), annotation.value()[0], name + " 权限码不对");
        }
    }

    /** 查询条件与分页参数要原样透传给服务，页面上看到的筛选才生效。 */
    @Test
    void list_passesFiltersAndPaging() {
        when(auditLogService.pageQuery(any())).thenReturn(new Page<>());
        WmsAuditQueryDTO query = new WmsAuditQueryDTO();
        query.setBizNo("MO1");
        query.setChannel("ERP同步");

        controller.list(query, 2, 50);

        ArgumentCaptor<WmsAuditQueryDTO> captor = ArgumentCaptor.forClass(WmsAuditQueryDTO.class);
        verify(auditLogService).pageQuery(captor.capture());
        assertEquals("MO1", captor.getValue().getBizNo());
        assertEquals("ERP同步", captor.getValue().getChannel());
        assertEquals(2, captor.getValue().getPageNo());
        assertEquals(50, captor.getValue().getPageSize());
    }

    /** 空工单号不查库：一个空条件可能把整张表捞出来。 */
    @Test
    void impact_blankWorkOrderNo_rejected() {
        assertFalse(controller.impact("   ").isSuccess());
        org.mockito.Mockito.verifyNoInteractions(impactService);
    }

    /** 影响面查不到时要如实返回失败原因，不能吞成一个空对象。 */
    @Test
    void impact_failure_returnsError() {
        when(impactService.analyzeByWorkOrderNo("MO1")).thenThrow(new RuntimeException("工单号不能为空"));

        assertFalse(controller.impact("MO1").isSuccess());
    }

    @Test
    void impact_success_returnsData() {
        when(impactService.analyzeByWorkOrderNo("MO1"))
                .thenReturn(new org.jeecg.modules.wms.vo.WmsImpactVO());

        assertTrue(controller.impact("MO1").isSuccess());
    }

    /** 审计表本身不提供任何写端点：只能查。 */
    @Test
    void noWriteEndpoints() {
        for (Method method : WmsAuditController.class.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("save"), method.getName());
            assertFalse(method.getName().startsWith("delete"), method.getName());
            assertFalse(method.getName().startsWith("edit"), method.getName());
        }
    }
}
