package org.jeecg.modules.wms.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.entity.WmsConversionOrder;
import org.jeecg.modules.wms.entity.WmsDefectRecord;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.mapper.WmsDefectRecordMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsConversionService;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsTransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Method;

/**
 * @Description: {@link WmsAudit} 切面——HTTP 通道的审计采集（XT-002）。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li>业务异常原样抛回调用方（审计不能改写业务语义）</li>
 *   <li>审计自身失败被吞掉（fail-open，审计不能拖垮业务）</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Aspect
@Component
public class WmsAuditAspect {

    private final IWmsAuditLogService auditLogService;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 评审 I2：出库/调拨端点的 SpEL 只能拿到行主键（雪花 id）。
     * 这两个可选依赖用于把 id 换成业务单号（outboundNo/transferNo），
     * 并顺带把工单号放进 biz_ref——否则「凭单号反查」查不到 UI 动作。
     */
    @Autowired(required = false)
    private IWmsOutboundService outboundService;

    @Autowired(required = false)
    private IWmsTransferService transferService;

    /** 技术债 §5-16：改制单/不良记录端点的 SpEL 同样只拿得到雪花 id，回查换成业务单号。 */
    @Autowired(required = false)
    private IWmsConversionService conversionService;

    @Autowired(required = false)
    private WmsDefectRecordMapper defectRecordMapper;

    public WmsAuditAspect(IWmsAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Around("@annotation(wmsAudit)")
    public Object around(ProceedingJoinPoint joinPoint, WmsAudit wmsAudit) throws Throwable {
        long start = System.currentTimeMillis();
        // 单号在 proceed 之前取：万一业务改写了入参，仍以调用时为准
        String bizNo = resolveBizNo(joinPoint, wmsAudit.bizNoSpEL());
        ResolvedBiz resolved = resolveBusinessNo(wmsAudit.bizType(), bizNo);
        String operator = WmsUtils.currentUsername();
        String ip = resolveIp();

        try {
            Object result = joinPoint.proceed();
            // 控制器普遍把业务异常吞成 Result.error(...)：此时方法本身正常返回，
            // 只有看返回值才知道这次操作其实失败了——否则审计会把失败记成成功。
            if (result instanceof Result && !((Result<?>) result).isSuccess()) {
                writeQuietly(wmsAudit, resolved, operator, ip, WmsConstants.AUDIT_RESULT_FAILED,
                        ((Result<?>) result).getMessage(), System.currentTimeMillis() - start);
                return result;
            }
            writeQuietly(wmsAudit, resolved, operator, ip, WmsConstants.AUDIT_RESULT_SUCCESS, null,
                    System.currentTimeMillis() - start);
            return result;
        } catch (Throwable ex) {
            writeQuietly(wmsAudit, resolved, operator, ip, WmsConstants.AUDIT_RESULT_FAILED, ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    /** 业务单号 + 关联单号的解析结果；未解析时 bizRef 为 null。 */
    private record ResolvedBiz(String bizNo, String bizRef) {
    }

    private void writeQuietly(WmsAudit wmsAudit, ResolvedBiz resolved, String operator, String ip,
                              String result, String errorMsg, long costMs) {
        try {
            auditLogService.record(new WmsAuditEvent()
                    .setBizType(wmsAudit.bizType())
                    .setBizNo(resolved.bizNo())
                    .setBizRef(resolved.bizRef())
                    .setAction(wmsAudit.action())
                    .setChannel(wmsAudit.channel())
                    .setOperator(operator)
                    .setIp(ip)
                    .setResult(result)
                    .setErrorMsg(errorMsg)
                    .setCostMs((int) Math.min(costMs, Integer.MAX_VALUE)));
        } catch (Exception e) {
            // record() 本身是 fail-open 的，这里再兜一层：将来换成别的实现也不会波及业务
            log.error("@WmsAudit 审计写入异常（已忽略，不影响业务）：bizType={}, bizNo={}",
                    wmsAudit.bizType(), resolved == null ? null : resolved.bizNo(), e);
        }
    }

    /** 解析 SpEL；取不到或解析出错都返回 null，交给审计服务按「无单号」处理。 */
    private String resolveBizNo(ProceedingJoinPoint joinPoint, String spel) {
        if (oConvertUtils.isEmpty(spel)) {
            return null;
        }
        try {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            // 走接口/代理时要拿到真实实现方法，否则参数名发现会失败
            Method target = AopUtils.getMostSpecificMethod(method, joinPoint.getTarget().getClass());
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                    null, target, joinPoint.getArgs(), parameterNameDiscoverer);
            Expression expression = expressionParser.parseExpression(spel);
            Object value = expression.getValue(context);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("@WmsAudit 业务单号解析失败（已置空）：spel={}", spel, e);
            return null;
        }
    }

    /**
     * SpEL 解析出来的是 19 位雪花主键时，回查单据把它换成业务单号；
     * 顺带把工单号放进 biz_ref，影响面时间线（按 biz_ref=工单号查）才能串到 UI 动作。
     * 解析失败保留原值——审计宁可记 id，也不能因为回查失败丢记录。
     */
    private ResolvedBiz resolveBusinessNo(String bizType, String bizNo) {
        if (bizNo == null || !bizNo.matches("\\d{18,20}")) {
            return new ResolvedBiz(bizNo, null);
        }
        try {
            if (WmsConstants.AUDIT_BIZ_OUTBOUND.equals(bizType) && outboundService != null) {
                WmsOutboundOrder order = outboundService.getById(bizNo);
                if (order != null && oConvertUtils.isNotEmpty(order.getOutboundNo())) {
                    return new ResolvedBiz(order.getOutboundNo(), order.getWorkOrderNo());
                }
            } else if (WmsConstants.AUDIT_BIZ_TRANSFER.equals(bizType) && transferService != null) {
                WmsTransferOrder transfer = transferService.getById(bizNo);
                if (transfer != null && oConvertUtils.isNotEmpty(transfer.getTransferNo())) {
                    return new ResolvedBiz(transfer.getTransferNo(), null);
                }
            } else if (WmsConstants.AUDIT_BIZ_CONVERSION.equals(bizType) && conversionService != null) {
                WmsConversionOrder conversion = conversionService.getById(bizNo);
                if (conversion != null && oConvertUtils.isNotEmpty(conversion.getConversionNo())) {
                    // bizRef 放生产订单号：影响面时间线按工单串起来时能和工单动作对齐
                    return new ResolvedBiz(conversion.getConversionNo(), conversion.getProductionOrderNo());
                }
            } else if (WmsConstants.AUDIT_BIZ_DEFECT_RECORD.equals(bizType) && defectRecordMapper != null) {
                WmsDefectRecord record = defectRecordMapper.selectById(bizNo);
                if (record != null && oConvertUtils.isNotEmpty(record.getSourceBillNo())) {
                    // 不良记录本身没有业务单号，用来源单号（入库单/出库单）作 bizNo
                    return new ResolvedBiz(record.getSourceBillNo(), record.getMaterialCode());
                }
            }
        } catch (Exception e) {
            log.warn("@WmsAudit 业务单号回查失败，保留原值：{}", bizNo, e);
        }
        return new ResolvedBiz(bizNo, null);
    }

    /** 非 Web 线程（如异步任务里）取不到请求，返回 null。 */
    private static String resolveIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return null;
            }
            HttpServletRequest request = attributes.getRequest();
            return request == null ? null : request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}
