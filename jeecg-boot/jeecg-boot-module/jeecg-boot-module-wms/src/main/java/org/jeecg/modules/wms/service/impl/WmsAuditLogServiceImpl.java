package org.jeecg.modules.wms.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsAuditQueryDTO;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * @Description: 审计留痕写入服务（XT-002 的唯一写入口）。
 *
 * <p>三条不变式：字段落全、没单号不写、写失败不抛。所有长度按 DDL 列宽截断——
 * 一条超长脏数据导致整表插入失败，等于这段操作完全没留痕。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Slf4j
@Service
public class WmsAuditLogServiceImpl implements IWmsAuditLogService {

    /** DDL 列宽，截断用。 */
    private static final int MAX_BIZ_TYPE = 32;
    private static final int MAX_BIZ_NO = 64;
    private static final int MAX_BIZ_REF = 64;
    private static final int MAX_ACTION = 32;
    private static final int MAX_CHANNEL = 32;
    private static final int MAX_DIFF_FIELDS = 1000;
    private static final int MAX_OPERATOR = 100;
    private static final int MAX_IP = 64;
    private static final int MAX_REQUEST_ID = 64;
    private static final int MAX_RESULT = 20;
    private static final int MAX_ERROR_MSG = 500;

    private final WmsAuditLogMapper auditLogMapper;

    public WmsAuditLogServiceImpl(WmsAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(WmsAuditEvent event) {
        try {
            if (event == null) {
                return;
            }
            String bizNo = trim(event.getBizNo());
            if (oConvertUtils.isEmpty(bizNo)) {
                log.warn("审计事件缺少业务单号，已跳过：bizType={}, action={}, channel={}",
                        event.getBizType(), event.getAction(), event.getChannel());
                return;
            }

            String beforeJson = toJson(event.getBeforeValue());
            String afterJson = toJson(event.getAfterValue());

            WmsAuditLog row = new WmsAuditLog()
                    .setId(IdWorker.getIdStr())
                    .setBizType(cut(event.getBizType(), MAX_BIZ_TYPE))
                    .setBizNo(cut(bizNo, MAX_BIZ_NO))
                    .setBizRef(cut(trim(event.getBizRef()), MAX_BIZ_REF))
                    .setAction(cut(event.getAction(), MAX_ACTION))
                    .setChannel(cut(event.getChannel(), MAX_CHANNEL))
                    .setBeforeValue(beforeJson)
                    .setAfterValue(afterJson)
                    .setDiffFields(cut(diffFields(beforeJson, afterJson), MAX_DIFF_FIELDS))
                    .setOperator(cut(orDefault(event.getOperator(), WmsConstants.AUDIT_OPERATOR_SYSTEM), MAX_OPERATOR))
                    .setIp(cut(trim(event.getIp()), MAX_IP))
                    .setRequestId(cut(trim(event.getRequestId()), MAX_REQUEST_ID))
                    .setResult(cut(orDefault(event.getResult(), WmsConstants.AUDIT_RESULT_SUCCESS), MAX_RESULT))
                    .setErrorMsg(cut(trim(event.getErrorMsg()), MAX_ERROR_MSG))
                    .setCostMs(event.getCostMs())
                    .setCreateTime(new Date());

            auditLogMapper.insert(row);
        } catch (Exception e) {
            // fail-open：审计失败绝不向上抛。回写场景里金蝶可能已落单，回滚只会两边不一致。
            log.error("审计留痕写入失败（已忽略，不影响业务）：bizType={}, bizNo={}",
                    event == null ? null : event.getBizType(),
                    event == null ? null : event.getBizNo(), e);
        }
    }

    @Override
    public IPage<WmsAuditLog> pageQuery(WmsAuditQueryDTO query) {
        WmsAuditQueryDTO q = query == null ? new WmsAuditQueryDTO() : query;
        LambdaQueryWrapper<WmsAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (oConvertUtils.isNotEmpty(q.getBizType())) {
            wrapper.eq(WmsAuditLog::getBizType, q.getBizType());
        }
        if (oConvertUtils.isNotEmpty(q.getBizNo())) {
            wrapper.like(WmsAuditLog::getBizNo, q.getBizNo().trim());
        }
        if (oConvertUtils.isNotEmpty(q.getOperator())) {
            wrapper.eq(WmsAuditLog::getOperator, q.getOperator().trim());
        }
        if (oConvertUtils.isNotEmpty(q.getChannel())) {
            wrapper.eq(WmsAuditLog::getChannel, q.getChannel());
        }
        if (oConvertUtils.isNotEmpty(q.getResult())) {
            wrapper.eq(WmsAuditLog::getResult, q.getResult());
        }
        if (q.getStartTime() != null) {
            wrapper.ge(WmsAuditLog::getCreateTime, q.getStartTime());
        }
        if (q.getEndTime() != null) {
            wrapper.le(WmsAuditLog::getCreateTime, q.getEndTime());
        }
        // create_time 只到秒，批量同步时大量行同秒；同值排序不稳定会让翻页重复/漏行
        wrapper.orderByDesc(WmsAuditLog::getCreateTime).orderByDesc(WmsAuditLog::getId);
        int pageNo = q.getPageNo() == null || q.getPageNo() < 1 ? 1 : q.getPageNo();
        int pageSize = q.getPageSize() == null || q.getPageSize() < 1 ? 10 : Math.min(q.getPageSize(), 200);
        return auditLogMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
    }

    @Override
    public String snapshot(Object bean, String... fields) {
        if (bean == null || fields == null || fields.length == 0) {
            return null;
        }
        try {
            JSONObject all;
            if (bean instanceof JSONObject) {
                all = (JSONObject) bean;
            } else if (bean instanceof java.util.Map) {
                all = new JSONObject((java.util.Map<?, ?>) bean);
            } else {
                all = JSON.parseObject(JSON.toJSONString(bean));
            }
            if (all == null) {
                return null;
            }
            JSONObject picked = new JSONObject();
            for (String field : fields) {
                if (oConvertUtils.isNotEmpty(field) && all.containsKey(field)) {
                    picked.put(field, all.get(field));
                }
            }
            return picked.isEmpty() ? null : picked.toJSONString();
        } catch (Exception e) {
            log.warn("审计快照生成失败（已忽略）：fields={}", Arrays.toString(fields), e);
            return null;
        }
    }

    /** 两个 JSON 的键差集：只报值真正变化的字段，按名字排序保证可复现。 */
    static String diffFields(String beforeJson, String afterJson) {
        if (beforeJson == null && afterJson == null) {
            return null;
        }
        JSONObject before = parse(beforeJson);
        JSONObject after = parse(afterJson);
        Set<String> keys = new LinkedHashSet<>();
        if (before != null) {
            keys.addAll(before.keySet());
        }
        if (after != null) {
            keys.addAll(after.keySet());
        }
        Set<String> changed = new TreeSet<>();
        for (String key : keys) {
            Object b = before == null ? null : before.get(key);
            Object a = after == null ? null : after.get(key);
            if (!sameValue(b, a)) {
                changed.add(key);
            }
        }
        return changed.isEmpty() ? null : String.join(",", changed);
    }

    private static JSONObject parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            // 非对象形态（数组/标量）不参与 diff，也不该因此丢整条日志
            return null;
        }
    }

    /** JSON 反序列化后数值类型可能不同（10 vs 10L），按字符串比较避免误报差异。 */
    private static boolean sameValue(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            // 已经是 JSON 字符串的调用方直接透传，避免二次转义
            String s = (String) value;
            return s.trim().isEmpty() ? null : s;
        }
        try {
            return JSON.toJSONString(value);
        } catch (Exception e) {
            log.warn("审计前后值序列化失败，已置空：type={}", value.getClass().getName(), e);
            return null;
        }
    }

    private static String orDefault(String value, String fallback) {
        String v = trim(value);
        return v == null ? fallback : v;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
