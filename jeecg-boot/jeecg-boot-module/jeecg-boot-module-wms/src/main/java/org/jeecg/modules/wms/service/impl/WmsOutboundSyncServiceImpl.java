package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.sync.IWmsOutboundSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * @Description: 出库同步重试（照 RK-002 的同步编排）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsOutboundSyncServiceImpl implements IWmsOutboundSyncService {

    private final IWmsOutboundSyncGateway gateway;
    private final WmsOutboundOrderMapper orderMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final ISysBaseAPI sysBaseAPI;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsOutboundSyncServiceImpl(IWmsOutboundSyncGateway gateway,
                                      WmsOutboundOrderMapper orderMapper,
                                      WmsOutboundDetailMapper detailMapper,
                                      ISysBaseAPI sysBaseAPI) {
        this.gateway = gateway;
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.sysBaseAPI = sysBaseAPI;
    }

    /** 仅供单测把退避时间置零。 */
    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsOrderSyncAttempt sync(String outboundId, boolean announce) {
        WmsOutboundOrder order = orderMapper.selectById(outboundId);
        if (order == null) {
            return new WmsOrderSyncAttempt(false, "出库单不存在", 0);
        }
        List<WmsOutboundDetail> details = detailMapper.selectList(Wrappers.<WmsOutboundDetail>query()
                .eq("outbound_id", outboundId).orderByAsc("line_no"));
        WmsOrderSyncResult last = null;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            last = gateway.sync(order, details);
            if (last.isSuccess()) {
                log.info("出库单[{}]同步成功，重试次数={}，mocked={}", order.getOutboundNo(), i, last.isMocked());
                return new WmsOrderSyncAttempt(true, null, i, last.isMocked());
            }
            if (i < WmsConstants.SYNC_MAX_RETRY) {
                sleepBeforeRetry(i, order.getOutboundNo());
            }
        }
        String error = truncate(last == null ? "未知原因" : last.getErrorMessage());
        log.error("出库单[{}]同步失败，已重试{}次，原因：{}",
                order.getOutboundNo(), WmsConstants.SYNC_MAX_RETRY, error);
        if (announce) {
            alert(WmsConstants.OUTBOUND_SYNC_ALERT_TITLE, order, error);
        }
        return new WmsOrderSyncAttempt(false, error, WmsConstants.SYNC_MAX_RETRY);
    }

    @Override
    public void persistResult(String id, WmsOrderSyncAttempt attempt) {
        orderMapper.persistSyncResult(id,
                attempt.isSuccess() ? WmsConstants.SYNC_STATUS_SUCCESS : WmsConstants.SYNC_STATUS_FAILED,
                attempt.getRetryCount(), truncate(attempt.getErrorMessage()));
    }

    @Override
    public boolean announceFailureIfNeeded(String outboundId) {
        WmsOutboundOrder order = orderMapper.selectById(outboundId);
        if (order == null) {
            return false;
        }
        if (!WmsConstants.SYNC_STATUS_FAILED.equals(order.getSyncStatus())) {
            return false;
        }
        // 只在恰好达到阈值那一轮发一次，避免每 5 分钟重复轰炸
        if (nvl(order.getSyncFailCount()) != WmsConstants.SYNC_ALERT_AFTER_FAILS) {
            return false;
        }
        alert(WmsConstants.OUTBOUND_SYNC_ALERT_TITLE, order, truncate(order.getLastSyncError()));
        return true;
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public Date syncStaleBefore() {
        return new Date(System.currentTimeMillis()
                - WmsConstants.OUTBOUND_SYNC_STALE_MINUTES * 60_000L);
    }

    @Override
    public int claimForSync(String id, Date staleBefore) {
        return orderMapper.claimForSync(id, staleBefore);
    }

    @Override
    public List<WmsOutboundOrder> selectSyncCandidates(Date staleBefore, int limit) {
        return orderMapper.selectSyncCandidates(staleBefore, limit);
    }

    @Override
    public List<WmsOutboundOrder> selectUndispatchedBefore(Date before, int limit) {
        return orderMapper.selectUndispatchedBefore(before, limit);
    }

    private void sleepBeforeRetry(int index, String no) {
        long wait = index < backoffMs.length ? backoffMs[index] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}]同步退避等待被中断", no);
        }
    }

    private void alert(String title, WmsOutboundOrder order, String error) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    alertToUser == null ? WmsConstants.ALERT_TO_USER : alertToUser, title,
                    "出库单：" + order.getOutboundNo() + "；重试次数："
                            + WmsConstants.SYNC_MAX_RETRY + "；失败原因：" + error));
        } catch (Exception e) {
            log.error("出库单[{}]同步失败告警投递失败：{}", order.getOutboundNo(), e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }
}
