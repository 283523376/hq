package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsInboundOrder;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.service.IWmsInboundSyncService;
import org.jeecg.modules.wms.sync.WmsInboundErpSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description: 入库单同步重试编排。不写库（结果由调用方 persistSyncResult 落库）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsInboundSyncServiceImpl implements IWmsInboundSyncService {

    private final WmsInboundErpSyncGateway gateway;
    private final WmsInboundDetailMapper detailMapper;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsInboundSyncServiceImpl(WmsInboundErpSyncGateway gateway, WmsInboundDetailMapper detailMapper) {
        this.gateway = gateway;
        this.detailMapper = detailMapper;
    }

    /** 仅供单测把退避时间置零。 */
    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsOrderSyncAttempt syncInbound(WmsInboundOrder order, boolean announce) {
        List<WmsInboundDetail> details = detailMapper.selectList(
                Wrappers.<WmsInboundDetail>query().eq("inbound_id", order.getId()));
        WmsOrderSyncResult last = null;
        int attemptNo = 0;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            attemptNo = i;
            last = gateway.sync(order, details);
            if (last.isSuccess()) {
                log.info("入库单[{}]同步 ERP 成功，重试次数={}", order.getOrderNo(), i);
                return new WmsOrderSyncAttempt(true, null, i);
            }
            if (i < WmsConstants.SYNC_MAX_RETRY) {
                sleepBeforeRetry(i, order);
            }
        }
        String error = truncate(last == null ? "未知原因" : last.getErrorMessage());
        log.error("入库单[{}]同步 ERP 失败，已重试{}次，失败原因：{}",
                order.getOrderNo(), WmsConstants.SYNC_MAX_RETRY, error);
        if (announce) {
            alert(order, error);
        }
        return new WmsOrderSyncAttempt(false, error, attemptNo);
    }

    private void sleepBeforeRetry(int i, WmsInboundOrder order) {
        long wait = i < backoffMs.length ? backoffMs[i] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("入库单[{}]同步退避等待被中断", order.getOrderNo());
        }
    }

    /** 站内消息告警；告警设施自身异常不得掩盖同步失败。 */
    private void alert(WmsInboundOrder order, String error) {
        try {
            String content = String.format("入库单号：%s；采购订单号：%s；供应商：%s；重试次数：%d；失败原因：%s",
                    order.getOrderNo(), order.getPurchaseOrderNo(), order.getSupplierName(),
                    WmsConstants.SYNC_MAX_RETRY, error);
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.INBOUND_SYNC_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("入库单[{}]同步失败告警投递失败：{}", order.getOrderNo(), e.getMessage(), e);
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
