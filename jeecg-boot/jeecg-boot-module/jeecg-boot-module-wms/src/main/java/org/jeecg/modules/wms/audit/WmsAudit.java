package org.jeecg.modules.wms.audit;

import org.jeecg.modules.wms.constant.WmsConstants;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Description: 标记需要留痕的 HTTP 入口方法（XT-002）。
 *
 * <p>只用于有 HTTP 请求上下文的入口（Controller 或带登录人的 service 方法）。
 * 定时任务/回写/设备回调没有请求上下文，必须显式调用 {@code IWmsAuditLogService.record}，
 * 否则会得到一堆没有操作人的记录——这正是 {@code wms_sync_log} 今天的毛病。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WmsAudit {

    /** 业务类型，取 {@code WmsConstants.AUDIT_BIZ_*}。 */
    String bizType();

    /** 动作，取 {@code WmsConstants.AUDIT_ACTION_*}。 */
    String action();

    /**
     * 取业务单号的 SpEL 表达式，如 {@code #id}、{@code #dto.workOrderNo}。
     * 留空表示该方法取不到单号（此时审计服务会 WARN 后跳过，不落库）。
     */
    String bizNoSpEL() default "";

    /** 通道，默认界面操作。 */
    String channel() default WmsConstants.AUDIT_CHANNEL_UI;
}
