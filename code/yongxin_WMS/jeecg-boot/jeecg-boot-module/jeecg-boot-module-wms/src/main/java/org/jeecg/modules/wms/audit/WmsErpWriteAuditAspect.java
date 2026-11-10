package org.jeecg.modules.wms.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * @Description: 回写 ERP 的审计采集（XT-002，通道=回写ERP）。
 *
 * <p>**为什么用切面而不是在每个回写器里加代码**：所有回写器都实现 {@code I*Writer}，
 * 返回 {@link WmsOrderSyncResult}（旧通道）或 {@link WmsKingdeeResult}（V2.4 新通道），
 * 切点同时覆盖两种返回类型，一个切面就能覆盖入库/领料/销售出库/调拨/不良品/样品/外协/盘点/
 * 受托入库/采购入库/采购退货全部通道，包括将来新增的回写器。逐个改实现类既容易漏，
 * 又会让 8 个大文件的构造器各加一个可选依赖。
 *
 * <p>前后值取同一个入参对象：回写器成功时会把 ERP 单号写回该对象，所以
 * {@code after_value} 天然带上「这次回写生成了哪张 ERP 单」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Aspect
@Component
public class WmsErpWriteAuditAspect {

    /** 与 wms_audit_log 的 before/after 体积平衡：只取能定位问题的那几个字段。 */
    private static final String[] SNAPSHOT_FIELDS = {
            "inboundNo", "outboundNo", "transferNo", "stocktakeNo",
            "workOrderNo", "salesOrderNo", "erpBillNo", "syncStatus", "status"
    };

    private static final String[] BILL_NO_GETTERS = {
            "getInboundNo", "getOutboundNo", "getTransferNo", "getStocktakeNo",
            // V2.4 新增回写器的入参类型：入库单用 orderNo、不良品记录用来源单号
            "getOrderNo", "getSourceBillNo"
    };

    private final IWmsAuditLogService auditLogService;

    public WmsErpWriteAuditAspect(IWmsAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Around("execution(org.jeecg.modules.wms.sync.WmsOrderSyncResult "
            + "org.jeecg.modules.wms.sync.I*Writer+.*(..))"
            + " || execution(org.jeecg.modules.wms.sync.WmsOrderSyncResult "
            + "org.jeecg.modules.wms.service.impl.WmsStocktakeErpWriterImpl.*(..))"
            // V2.4 新增的回写器返回 WmsKingdeeResult（而不是 WmsOrderSyncResult），
            // 不并入切点就会漏掉受托入库/采购入库/采购退货三条回写的审计行。
            + " || execution(org.jeecg.modules.wms.sync.WmsKingdeeResult "
            + "org.jeecg.modules.wms.sync.I*Writer+.*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        Object target = args.length == 0 ? null : args[0];
        String before = snapshot(target);
        long start = System.currentTimeMillis();

        try {
            Object result = joinPoint.proceed();
            writeQuietly(target, before, outcomeOf(result, null), start);
            return result;
        } catch (Throwable ex) {
            writeQuietly(target, before, outcomeOf(null, ex.getMessage()), start);
            throw ex;
        }
    }

    /** 两种回写返回类型的统一视图：成败、错误文案、ERP 单号。 */
    private record WriteOutcome(boolean success, String errorMsg, String erpBillNo) {
    }

    private static WriteOutcome outcomeOf(Object result, String errorMsg) {
        if (result instanceof WmsKingdeeResult kd) {
            return new WriteOutcome(kd.isSuccess(), kd.isSuccess() ? null : kd.getMessage(), kd.getNumber());
        }
        if (result instanceof WmsOrderSyncResult sync) {
            return new WriteOutcome(sync.isSuccess(), sync.getErrorMessage(), null);
        }
        // 无返回类型可解析（含异常路径）：沿用原口径——无异常即成功
        return new WriteOutcome(errorMsg == null, errorMsg, null);
    }

    private void writeQuietly(Object target, String before, WriteOutcome outcome, long start) {
        try {
            WmsAuditEvent event = new WmsAuditEvent()
                    .setBizType(bizTypeOf(target))
                    .setBizNo(billNoOf(target))
                    .setAction(WmsConstants.AUDIT_ACTION_WRITE_BACK)
                    .setChannel(WmsConstants.AUDIT_CHANNEL_ERP_WRITE)
                    .setBeforeValue(before)
                    .setAfterValue(afterSnapshot(target, outcome.erpBillNo()))
                    .setOperator(WmsConstants.AUDIT_OPERATOR_SYSTEM)
                    .setResult(outcome.success()
                            ? WmsConstants.AUDIT_RESULT_SUCCESS : WmsConstants.AUDIT_RESULT_FAILED)
                    .setErrorMsg(outcome.errorMsg())
                    .setCostMs((int) Math.min(System.currentTimeMillis() - start, Integer.MAX_VALUE));
            auditLogService.record(event);
        } catch (Exception e) {
            log.error("回写审计写入异常（已忽略，不影响业务）", e);
        }
    }

    /** 回写后快照；WmsKingdeeResult 的单号没有写回实体，单独合并进 after_value。 */
    private String afterSnapshot(Object target, String erpBillNo) {
        String snapshot = snapshot(target);
        if (oConvertUtils.isEmpty(erpBillNo)) {
            return snapshot;
        }
        com.alibaba.fastjson2.JSONObject json = oConvertUtils.isEmpty(snapshot)
                ? new com.alibaba.fastjson2.JSONObject() : com.alibaba.fastjson2.JSON.parseObject(snapshot);
        json.put("erpBillNo", erpBillNo);
        return json.toJSONString();
    }

    private String snapshot(Object target) {
        if (target == null) {
            return null;
        }
        try {
            return auditLogService.snapshot(target, SNAPSHOT_FIELDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bizTypeOf(Object target) {
        if (target instanceof WmsProductionInbound || target instanceof WmsInboundOrder) {
            return WmsConstants.AUDIT_BIZ_INBOUND;
        }
        if (target instanceof WmsOutboundOrder) {
            return WmsConstants.AUDIT_BIZ_OUTBOUND;
        }
        if (target instanceof WmsTransferOrder) {
            return WmsConstants.AUDIT_BIZ_TRANSFER;
        }
        if (target instanceof WmsStocktakeOrder) {
            return WmsConstants.AUDIT_BIZ_STOCKTAKE;
        }
        if (target instanceof WmsDefectRecord) {
            return WmsConstants.AUDIT_BIZ_DEFECT_RECORD;
        }
        return WmsConstants.AUDIT_BIZ_OUTBOUND;
    }

    private static String billNoOf(Object target) {
        if (target == null) {
            return null;
        }
        for (String getter : BILL_NO_GETTERS) {
            try {
                Method method = target.getClass().getMethod(getter);
                Object value = method.invoke(target);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // 该实体没有这个 getter，继续下一个
            }
        }
        return null;
    }
}
