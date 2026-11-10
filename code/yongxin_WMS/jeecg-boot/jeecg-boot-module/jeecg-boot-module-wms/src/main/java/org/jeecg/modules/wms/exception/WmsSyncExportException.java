package org.jeecg.modules.wms.exception;

/**
 * @Description: SB-001 待推送包导出失败（spec 2026-11-03 §2.10）。
 *
 * <p>导出端点是流式响应，不能返回 {@code Result<T>}；失败必须靠异常传递，
 * 才能让 {@code WmsAuditAspect} 记「失败」并由控制器专用异常处理器回写错误体。
 *
 * <p><b>必须是专用类型</b>：如果控制器直接处理 {@code RuntimeException}，
 * 会把权限异常（Shiro）和其它端点的底层异常一并接管，导致平台统一的 401/510 响应与
 * {@code sys_log} 全部失效。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
public class WmsSyncExportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WmsSyncExportException(String message) {
        super(message);
    }

    public WmsSyncExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
