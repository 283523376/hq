package org.jeecg.modules.wms.audit;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.service.IWmsAuditLogService;

/**
 * @Description: 非 HTTP 通道的审计落点（定时任务 / ERP 同步 / 回写）。
 *
 * <p>为什么需要它：这些场景的 service 用构造器注入，为了不打断既有单测（它们按旧构造器 new 服务），
 * 审计服务在那些类里是**可选**依赖（`@Autowired(required = false)`）。可能为 null，
 * 所以统一走这里——null 直接跳过，任何异常只记日志（与 {@code record()} 同样是 fail-open）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
public final class WmsAuditRecorder {

    private WmsAuditRecorder() {
    }

    /** 主数据（物料/仓库/分类/仓位）同步落地的统一留痕。 */
    public static void recordMasterData(IWmsAuditLogService service, String bizNo,
                                        String action, Object bean) {
        record(service, new WmsAuditEvent()
                .setBizType(WmsConstants.AUDIT_BIZ_MASTER_DATA)
                .setBizNo(bizNo)
                .setAction(action)
                .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_SYNC)
                .setAfterValue(snapshot(service, bean,
                        "materialCode", "warehouseCode", "categoryCode", "binNumber",
                        "materialName", "warehouseName", "erpStockCode", "syncStatus", "status"))
                .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                .setResult(WmsConstants.AUDIT_RESULT_SUCCESS));
    }

    /** null-safe 快照：审计服务不存在（单测未注入）时返回 null，不写半个 JSON。 */
    public static String snapshot(IWmsAuditLogService auditLogService, Object bean, String... fields) {
        if (auditLogService == null || bean == null) {
            return null;
        }
        try {
            return auditLogService.snapshot(bean, fields);
        } catch (Exception e) {
            log.warn("审计快照生成异常（已忽略）", e);
            return null;
        }
    }

    public static void record(IWmsAuditLogService auditLogService, WmsAuditEvent event) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.record(event);
        } catch (Exception e) {
            log.error("审计留痕写入异常（已忽略，不影响业务）：bizNo={}",
                    event == null ? null : event.getBizNo(), e);
        }
    }
}
