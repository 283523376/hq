package org.jeecg.modules.wms.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.service.IWmsProductionSyncService;
import org.jeecg.modules.wms.sync.IWmsProductionInboundSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * @Description: 生产入库同步重试编排（照 RK-001 的入库同步实现）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsProductionSyncServiceImpl implements IWmsProductionSyncService {

    private final IWmsProductionInboundSyncGateway gateway;
    private final WmsProductionInboundMapper inboundMapper;
    private final ISysBaseAPI sysBaseAPI;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsProductionSyncServiceImpl(IWmsProductionInboundSyncGateway gateway,
                                        WmsProductionInboundMapper inboundMapper,
                                        ISysBaseAPI sysBaseAPI) {
        this.gateway = gateway;
        this.inboundMapper = inboundMapper;
        this.sysBaseAPI = sysBaseAPI;
    }

    /** 仅供单测把退避时间置零。 */
    void setBackoffMsForTest(long[] backoffMs) {
        this.backoffMs = backoffMs;
    }

    @Override
    public WmsOrderSyncAttempt syncInbound(String inboundId, boolean announce) {
        WmsProductionInbound record = inboundMapper.selectById(inboundId);
        if (record == null) {
            return new WmsOrderSyncAttempt(false, "生产入库记录不存在", 0);
        }
        WmsOrderSyncResult last = null;
        int attemptNo = 0;
        for (int i = 0; i <= WmsConstants.SYNC_MAX_RETRY; i++) {
            attemptNo = i;
            last = gateway.sync(record);
            if (last.isSuccess()) {
                log.info("生产入库[{}]同步成功，重试次数={}，mocked={}",
                        record.getInboundNo(), i, last.isMocked());
                return new WmsOrderSyncAttempt(true, null, i);
            }
            if (i < WmsConstants.SYNC_MAX_RETRY) {
                sleepBeforeRetry(i, record);
            }
        }
        String error = truncate(last == null ? "未知原因" : last.getErrorMessage());
        log.error("生产入库[{}]同步失败，已重试{}次，原因：{}",
                record.getInboundNo(), WmsConstants.SYNC_MAX_RETRY, error);
        if (announce) {
            alert(record, error);
        }
        return new WmsOrderSyncAttempt(false, error, attemptNo);
    }

    @Override
    public WmsOrderSyncAttempt reconcileReportSourced(WmsProductionInbound record) {
        if (record == null) {
            return new WmsOrderSyncAttempt(false, "生产入库记录不存在", 0);
        }
        WmsOrderSyncResult result = gateway.sync(record);
        if (result.isSuccess()) {
            log.info("工序汇报入库[{}]对账成功，ERP 单号已回填", record.getInboundNo());
        }
        return new WmsOrderSyncAttempt(result.isSuccess(), result.getErrorMessage(), 0);
    }

    @Override
    public void alertRepeatedFailure(WmsProductionInbound record, String error) {
        alert(record, error);
    }

    private void sleepBeforeRetry(int index, WmsProductionInbound record) {
        long wait = index < backoffMs.length ? backoffMs[index] : backoffMs[backoffMs.length - 1];
        if (wait <= 0) {
            return;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("生产入库[{}]同步退避等待被中断", record.getInboundNo());
        }
    }

    private void alert(WmsProductionInbound record, String error) {
        try {
            String content = String.format("入库单号：%s；工单：%s；批次：%s；重试次数：%d；失败原因：%s",
                    record.getInboundNo(), record.getWorkOrderNo(), record.getBatchNo(),
                    WmsConstants.SYNC_MAX_RETRY, error);
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    alertToUser == null ? WmsConstants.ALERT_TO_USER : alertToUser,
                    WmsConstants.PRODUCTION_SYNC_ALERT_TITLE, content));
        } catch (Exception e) {
            log.error("生产入库[{}]同步失败告警投递失败：{}", record.getInboundNo(), e.getMessage(), e);
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
