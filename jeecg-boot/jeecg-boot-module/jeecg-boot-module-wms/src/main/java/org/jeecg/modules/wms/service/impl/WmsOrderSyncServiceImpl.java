package org.jeecg.modules.wms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOrder;
import org.jeecg.modules.wms.service.IWmsOrderSyncService;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.stereotype.Service;

/**
 * @Description: 订单同步重试编排。不写库。
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Slf4j
@Service
public class WmsOrderSyncServiceImpl implements IWmsOrderSyncService {

    private final WmsOrderSyncGateway gateway;
    private final ISysBaseAPI sysBaseAPI;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    public WmsOrderSyncServiceImpl(WmsOrderSyncGateway gateway, ISysBaseAPI sysBaseAPI) {
        this.gateway = gateway;
        this.sysBaseAPI = sysBaseAPI;
    }

    /** 仅供单测把退避时间置零，避免测试白等 7 秒。 */
    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsOrderSyncAttempt syncOrder(WmsOrder order, boolean announce) {
        WmsOrderSyncResult last = null;
        int attemptNo = 0;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            attemptNo = i;
            last = gateway.sync(order);
            if (last.isSuccess()) {
                log.info("订单[{}]同步 ERP/MES 成功，重试次数={}", order.getOrderNo(), i);
                return new WmsOrderSyncAttempt(true, null, i);
            }
            if (i < WmsConstants.SYNC_MAX_RETRY) {
                sleepBeforeRetry(i, order);
            }
        }
        String error = truncate(last == null ? "未知原因" : last.getErrorMessage());
        log.error("订单[{}]同步 ERP/MES 失败，已重试{}次，订单类型={}，供应商ID={}，失败原因：{}",
                order.getOrderNo(), WmsConstants.SYNC_MAX_RETRY, order.getOrderType(),
                order.getSupplierId(), error);
        if (announce) {
            alert(order, error);
        }
        return new WmsOrderSyncAttempt(false, error, attemptNo);
    }

    /**
     * 退避等待。下标 i 表示「第 i 次重试之前」，故取 backoffMs[i]。
     */
    private void sleepBeforeRetry(int i, WmsOrder order) {
        long wait = i < backoffMs.length ? backoffMs[i] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("订单[{}]同步退避等待被中断", order.getOrderNo());
        }
    }

    /**
     * 站内消息告警。用 try/catch 包住——告警设施自己出问题，不能掩盖同步失败这件事。
     */
    private void alert(WmsOrder order, String error) {
        try {
            String content = String.format("订单号：%s；订单类型：%s；重试次数：%d；失败原因：%s",
                    order.getOrderNo(), order.getOrderType(), WmsConstants.SYNC_MAX_RETRY, error);
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, null,
                    WmsConstants.SYNC_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("订单[{}]同步失败告警投递失败：{}", order.getOrderNo(), e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text
                : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }
}
