package org.jeecg.modules.wms.audit;

import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @Description: @WmsAudit 切面单测（不启 Spring 容器，用 AspectJProxyFactory 织入）。
 *
 * <p>切面自己出错绝不能改写业务结果：既不吞业务异常，也不能因为审计失败而让业务失败。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditAspectTest {

    private IWmsAuditLogService auditService;
    private WmsAuditAspect aspect;

    @BeforeEach
    void setUp() {
        auditService = mock(IWmsAuditLogService.class);
        aspect = new WmsAuditAspect(auditService);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private WmsAuditEvent capture() {
        ArgumentCaptor<WmsAuditEvent> captor = ArgumentCaptor.forClass(WmsAuditEvent.class);
        verify(auditService).record(captor.capture());
        return captor.getValue();
    }

    /** 业务单号从 SpEL 取，通道默认界面操作。 */
    @Test
    void aspect_recordsEventFromSpel() {
        Target target = proxy(new Target());

        target.confirm("OUT1");

        WmsAuditEvent event = capture();
        assertEquals("OUT1", event.getBizNo(), "bizNo 必须来自 bizNoSpEL=#id");
        assertEquals("出库单", event.getBizType());
        assertEquals("确认出库", event.getAction());
        assertEquals("界面操作", event.getChannel());
        assertEquals("成功", event.getResult());
        assertNotNull(event.getCostMs(), "耗时必须记录");
    }

    /** 业务抛异常：审计记失败且带上原因，异常照常抛回调用方。 */
    @Test
    void aspect_recordsFailureWithErrorMsg() {
        Target target = proxy(new Target());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> target.explode("OUT2"));
        assertEquals("库存不足", ex.getMessage(), "业务异常不能被切面改写");

        WmsAuditEvent event = capture();
        assertEquals("OUT2", event.getBizNo());
        assertEquals("失败", event.getResult());
        assertTrue(event.getErrorMsg().contains("库存不足"), "实际：" + event.getErrorMsg());
    }

    /** 审计服务自己炸了，业务返回值不受影响。 */
    @Test
    void aspect_auditThrows_businessStillReturns() {
        doThrow(new RuntimeException("audit down")).when(auditService).record(any(WmsAuditEvent.class));
        Target target = proxy(new Target());

        String result = assertDoesNotThrow(() -> target.ping("OUT3"));

        assertEquals("ok", result);
    }

    /** 嵌套属性表达式也能解析：#dto.workOrderNo。 */
    @Test
    void aspect_resolvesNestedProperty() {
        Target target = proxy(new Target());

        target.nested(new Dto("MO-9"));

        assertEquals("MO-9", capture().getBizNo());
    }

    static class Dto {
        private final String workOrderNo;

        Dto(String workOrderNo) {
            this.workOrderNo = workOrderNo;
        }

        public String getWorkOrderNo() {
            return workOrderNo;
        }
    }

    static class Target {

        @WmsAudit(bizType = "出库单", action = "确认出库", bizNoSpEL = "#id")
        public void confirm(String id) {
            // 业务本体
        }

        @WmsAudit(bizType = "出库单", action = "确认出库", bizNoSpEL = "#id")
        public void explode(String id) {
            throw new IllegalStateException("库存不足");
        }

        @WmsAudit(bizType = "出库单", action = "复核", bizNoSpEL = "#id")
        public String ping(String id) {
            return "ok";
        }

        @WmsAudit(bizType = "工单", action = "创建", bizNoSpEL = "#dto.workOrderNo")
        public void nested(Dto dto) {
            // 业务本体
        }

        @WmsAudit(bizType = "出库单", action = "分配", bizNoSpEL = "#id")
        public org.jeecg.common.api.vo.Result<?> softFail(String id) {
            return org.jeecg.common.api.vo.Result.error("出库单不存在");
        }

        @WmsAudit(bizType = "出库单", action = "分配", bizNoSpEL = "#id")
        public org.jeecg.common.api.vo.Result<?> softOk(String id) {
            return org.jeecg.common.api.vo.Result.OK("分配成功");
        }
    }

    /** 控制器吞异常返回 Result.error：审计必须记失败，不能因为方法正常返回就记成功。 */
    @Test
    void aspect_resultError_recordedAsFailed() {
        Target target = proxy(new Target());

        org.jeecg.common.api.vo.Result<?> result = target.softFail("OUT4");

        assertFalse(result.isSuccess());
        WmsAuditEvent event = capture();
        assertEquals("失败", event.getResult());
        assertTrue(event.getErrorMsg().contains("出库单不存在"), "实际：" + event.getErrorMsg());
    }

    /** 成功返回 Result.OK 仍然记成功。 */
    @Test
    void aspect_resultOk_recordedAsSuccess() {
        Target target = proxy(new Target());

        target.softOk("OUT5");

        assertEquals("成功", capture().getResult());
    }
}
