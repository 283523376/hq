package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.base.service.impl.JeecgServiceImpl;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSyncLog;
import org.jeecg.modules.wms.mapper.WmsSyncLogMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 上位系统同步日志服务（BD-002 引入，SB-001 共用底座）。
 *
 * <p>报文一律先 {@link #sanitize} 再落库：令牌/密钥替换为 ***，单条超 8KB 截断。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsSyncLogServiceImpl extends JeecgServiceImpl<WmsSyncLogMapper, WmsSyncLog>
        implements IWmsSyncLogService {

    /** 单条报文落库上限（字符），超出截断并追加标记。 */
    static final int MAX_PAYLOAD_LENGTH = 8192;
    private static final String TRUNCATED_SUFFIX = "[截断]";

    @Override
    public void logSync(String direction, String objectType, String objectNo, String target,
                        boolean success, int retryCount,
                        String requestPayload, String responsePayload,
                        String errorMessage, long costMs) {
        int effectiveRetry = retryCount;
        if (!success && oConvertUtils.isNotEmpty(objectNo)) {
            // 连续失败计数以日志为准：同单近 7 天已失败次数 + 1（评审 C2）
            Date since = new Date(System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L);
            Long prior = baseMapper.selectCount(Wrappers.<WmsSyncLog>query()
                    .eq("object_type", objectType)
                    .eq("object_no", objectNo)
                    .eq("status", WmsConstants.SYNC_STATUS_FAILED)
                    .ge("create_time", since));
            effectiveRetry = (prior == null ? 0 : prior.intValue()) + 1;
        }
        WmsSyncLog logRow = new WmsSyncLog()
                .setDirection(direction)
                .setObjectType(objectType)
                .setObjectNo(objectNo)
                .setTarget(target)
                .setStatus(success ? WmsConstants.SYNC_STATUS_SUCCESS : WmsConstants.SYNC_STATUS_FAILED)
                .setRetryCount(effectiveRetry)
                .setRequestPayload(sanitize(requestPayload))
                .setResponsePayload(sanitize(responsePayload))
                .setErrorMessage(truncate(errorMessage))
                .setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));
        WmsUtils.fillCreate(logRow);
        if (oConvertUtils.isEmpty(logRow.getCreateBy())) {
            logRow.setCreateBy("system");
        }
        if (logRow.getCreateTime() == null) {
            logRow.setCreateTime(new Date());
        }
        save(logRow);
    }

    @Override
    public List<String> listFailedObjectNos(String objectType, int maxRetry, int withinDays) {
        return latestPerObject(objectType, withinDays).stream()
                .filter(row -> WmsConstants.SYNC_STATUS_FAILED.equals(row.getStatus()))
                .filter(row -> row.getRetryCount() == null || row.getRetryCount() <= maxRetry)
                .map(WmsSyncLog::getObjectNo)
                .filter(oConvertUtils::isNotEmpty)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public List<String> listExhaustedObjectNos(String objectType, int maxRetry, int withinDays) {
        return latestPerObject(objectType, withinDays).stream()
                .filter(row -> WmsConstants.SYNC_STATUS_FAILED.equals(row.getStatus()))
                .filter(row -> row.getRetryCount() != null && row.getRetryCount() == maxRetry)
                .map(WmsSyncLog::getObjectNo)
                .filter(oConvertUtils::isNotEmpty)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public long latestSuccessTimeMillis(String objectType) {
        WmsSyncLog row = getOne(Wrappers.<WmsSyncLog>query()
                .eq("object_type", objectType)
                .eq("status", WmsConstants.SYNC_STATUS_SUCCESS)
                .orderByDesc("create_time")
                .last("LIMIT 1"), false);
        return row == null || row.getCreateTime() == null ? 0L : row.getCreateTime().getTime();
    }

    /**
     * 每个 object_no 取最新一条日志（按 create_time 倒序，首次出现即最新）。
     * 成功行会把该单从失败队列移除；失败行按最新 retry_count 判断是否继续重试。
     */
    private List<WmsSyncLog> latestPerObject(String objectType, int withinDays) {
        Date since = new Date(System.currentTimeMillis() - withinDays * 24L * 60L * 60L * 1000L);
        List<WmsSyncLog> rows = list(Wrappers.<WmsSyncLog>query()
                .eq("object_type", objectType)
                .ge("create_time", since)
                .orderByDesc("create_time")
                .last("LIMIT 5000"));
        Map<String, WmsSyncLog> latest = new LinkedHashMap<>();
        for (WmsSyncLog row : rows) {
            if (oConvertUtils.isNotEmpty(row.getObjectNo())) {
                latest.putIfAbsent(row.getObjectNo(), row);
            }
        }
        return new ArrayList<>(latest.values());
    }

    /**
     * 脱敏 + 截断：令牌/密钥替换为 ***；超 8192 字符截断并追加 [截断]。
     * 空报文返回 null（列存 NULL，不留空串）。
     */
    private static String truncate(String text) {
        if (text == null || text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH) {
            return text;
        }
        return text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    static String sanitize(String payload) {
        if (oConvertUtils.isEmpty(payload)) {
            return null;
        }
        String out = payload
                .replaceAll("(?i)(X-MES-Token\\s*[:=]\\s*)\\S+", "$1***")
                .replaceAll("(?i)(\"appSecret\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(access_token=)[^&\\s\"']+", "$1***")
                .replaceAll("(?i)(\"access_token\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)\\S+", "$1***")
                .replaceAll("(?i)(Cookie\\s*:\\s*)[^\\r\\n]+", "$1***");
        if (out.length() > MAX_PAYLOAD_LENGTH) {
            out = out.substring(0, MAX_PAYLOAD_LENGTH) + TRUNCATED_SUFFIX;
        }
        return out;
    }
}
