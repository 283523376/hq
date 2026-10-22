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
import java.util.List;
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
        WmsSyncLog logRow = new WmsSyncLog()
                .setDirection(direction)
                .setObjectType(objectType)
                .setObjectNo(objectNo)
                .setTarget(target)
                .setStatus(success ? WmsConstants.SYNC_STATUS_SUCCESS : WmsConstants.SYNC_STATUS_FAILED)
                .setRetryCount(retryCount)
                .setRequestPayload(sanitize(requestPayload))
                .setResponsePayload(sanitize(responsePayload))
                .setErrorMessage(errorMessage)
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
        Date since = new Date(System.currentTimeMillis() - withinDays * 24L * 60L * 60L * 1000L);
        List<WmsSyncLog> rows = list(Wrappers.<WmsSyncLog>query()
                .select("object_no")
                .eq("object_type", objectType)
                .eq("status", WmsConstants.SYNC_STATUS_FAILED)
                .lt("retry_count", maxRetry)
                .ge("create_time", since));
        Set<String> nos = new LinkedHashSet<>();
        for (WmsSyncLog row : rows) {
            if (oConvertUtils.isNotEmpty(row.getObjectNo())) {
                nos.add(row.getObjectNo());
            }
        }
        return new ArrayList<>(nos);
    }

    /**
     * 脱敏 + 截断：令牌/密钥替换为 ***；超 8192 字符截断并追加 [截断]。
     * 空报文返回 null（列存 NULL，不留空串）。
     */
    static String sanitize(String payload) {
        if (oConvertUtils.isEmpty(payload)) {
            return null;
        }
        String out = payload
                .replaceAll("(?i)(X-MES-Token\\s*[:=]\\s*)\\S+", "$1***")
                .replaceAll("(?i)(\"appSecret\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(?i)(access_token=)[^&\\s\"']+", "$1***");
        if (out.length() > MAX_PAYLOAD_LENGTH) {
            out = out.substring(0, MAX_PAYLOAD_LENGTH) + TRUNCATED_SUFFIX;
        }
        return out;
    }
}
