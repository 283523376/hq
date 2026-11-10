package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.mapper.WmsTransferDetailMapper;
import org.jeecg.modules.wms.mapper.WmsTransferOrderMapper;
import org.jeecg.modules.wms.service.IWmsTransferSyncService;
import org.jeecg.modules.wms.sync.IWmsKingdeeTransferWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeTransferStatus;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @Description: 调拨单 ERP 同步实现（CK-004 Phase B）。
 *
 * <p>双时钟口径：本类只动 ERP 时钟 {@code sync_status} 与派生的 {@code status}，
 * **不改** {@code exec_status}（实物时钟由执行段负责）。
 * 每次写库前用 {@code WmsTransferStatusResolver} 派生并校验迁移，终态不再推进。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsTransferSyncServiceImpl implements IWmsTransferSyncService {

    private final WmsTransferOrderMapper orderMapper;
    private final WmsTransferDetailMapper detailMapper;
    private final IWmsKingdeeTransferWriter writer;
    private final WmsKingdeeProperties properties;
    private final WmsTransferStatusResolver statusResolver;
    private final ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser;

    public WmsTransferSyncServiceImpl(WmsTransferOrderMapper orderMapper,
                                      WmsTransferDetailMapper detailMapper,
                                      IWmsKingdeeTransferWriter writer,
                                      WmsKingdeeProperties properties,
                                      WmsTransferStatusResolver statusResolver,
                                      ISysBaseAPI sysBaseAPI) {
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.writer = writer;
        this.properties = properties;
        this.statusResolver = statusResolver;
        this.sysBaseAPI = sysBaseAPI;
    }

    @Override
    public int submitPending() {
        List<WmsTransferOrder> candidates = orderMapper.selectList(new LambdaQueryWrapper<WmsTransferOrder>()
                .eq(WmsTransferOrder::getExecStatus, WmsConstants.TRANSFER_EXEC_COMPLETED)
                .eq(WmsTransferOrder::getStatus, WmsConstants.TRANSFER_STATUS_PENDING_ERP)
                .in(WmsTransferOrder::getSyncStatus, WmsConstants.SYNC_STATUS_PENDING, WmsConstants.SYNC_STATUS_FAILED)
                .lt(WmsTransferOrder::getRetryCount, WmsConstants.TRANSFER_SYNC_MAX_RETRY)
                .orderByAsc(WmsTransferOrder::getExecFinishTime)
                .last("LIMIT " + WmsConstants.TRANSFER_SYNC_BATCH_SIZE));
        int handled = 0;
        for (WmsTransferOrder order : candidates) {
            handled++;
            submitOne(order);
        }
        return handled;
    }

    @Override
    public int pollStatus() {
        List<WmsTransferOrder> candidates = orderMapper.selectList(new LambdaQueryWrapper<WmsTransferOrder>()
                .eq(WmsTransferOrder::getStatus, WmsConstants.TRANSFER_STATUS_PENDING_ERP)
                .eq(WmsTransferOrder::getSyncStatus, WmsConstants.TRANSFER_SYNC_SUBMITTED)
                .orderByAsc(WmsTransferOrder::getSubmitTime)
                .last("LIMIT " + WmsConstants.TRANSFER_SYNC_BATCH_SIZE));
        int handled = 0;
        for (WmsTransferOrder order : candidates) {
            handled++;
            pollOne(order);
        }
        return handled;
    }

    // ==================== 提交 ====================

    private void submitOne(WmsTransferOrder order) {
        WmsTransferOrder patch = new WmsTransferOrder();
        patch.setId(order.getId());
        List<WmsTransferDetail> details = detailMapper.selectList(new LambdaQueryWrapper<WmsTransferDetail>()
                .eq(WmsTransferDetail::getTransferId, order.getId())
                .orderByAsc(WmsTransferDetail::getLineNo));
        WmsOrderSyncResult result = writer.saveAndSubmit(order, details);
        if (result.isSuccess()) {
            patch.setSyncStatus(WmsConstants.TRANSFER_SYNC_SUBMITTED)
                    .setErpDocumentStatus(WmsConstants.KINGDEE_DOC_STATUS_SUBMITTED)
                    // erp_bill_no 只存金蝶真实单号（2026-10-28「ERP 单号为准」修正），
                    // 模拟提交没有真实单号就留空，不能拿 WMS 单号占位
                    .setSubmitTime(new Date())
                    .setLastSyncTime(new Date())
                    .setLastSyncError(result.isMocked() ? "金蝶未启用（模拟提交）" : null)
                    .setSyncFailCount(0);
            orderMapper.updateById(patch);
            if (!result.isMocked()) {
                // 技术债 §5-20：实体更新忽略 null，真实提交成功必须显式清掉上一次的失败原因
                clearLastSyncError(order.getId());
            }
            log.info("调拨单[{}]已提交金蝶（{}）", order.getTransferNo(),
                    result.isMocked() ? "模拟" : "真实");
            return;
        }
        int retry = (order.getRetryCount() == null ? 0 : order.getRetryCount()) + 1;
        int failCount = (order.getSyncFailCount() == null ? 0 : order.getSyncFailCount()) + 1;
        patch.setSyncStatus(WmsConstants.SYNC_STATUS_FAILED)
                .setRetryCount(retry)
                .setSyncFailCount(failCount)
                .setLastSyncError(truncate(result.getErrorMessage()))
                .setLastSyncTime(new Date());
        orderMapper.updateById(patch);
        log.warn("调拨单[{}]提交金蝶失败（第 {} 次）：{}", order.getTransferNo(), retry, result.getErrorMessage());
        if (failCount == WmsConstants.TRANSFER_SYNC_MAX_RETRY) {
            sendAlert(WmsConstants.TRANSFER_SYNC_ALERT_TITLE,
                    "调拨单 " + order.getTransferNo() + " 连续 " + failCount + " 次提交金蝶失败："
                            + result.getErrorMessage());
        }
    }

    /** 技术债 §5-20：显式清空失败原因——实体更新走 NOT_NULL 策略，set null 不会落库。 */
    private void clearLastSyncError(String id) {
        orderMapper.update(null, new UpdateWrapper<WmsTransferOrder>()
                .set("last_sync_error", null)
                .eq("id", id));
    }

    // ==================== 回读 ====================

    private void pollOne(WmsTransferOrder order) {
        WmsKingdeeTransferStatus status = writer.queryStatus(
                oConvertUtils.isEmpty(order.getErpBillNo()) ? order.getTransferNo() : order.getErpBillNo());
        if (!status.isSuccess()) {
            WmsTransferOrder errorPatch = new WmsTransferOrder();
            errorPatch.setId(order.getId());
            errorPatch.setLastSyncError(truncate(status.getMessage()));
            errorPatch.setLastSyncTime(new Date());
            orderMapper.updateById(errorPatch);
            log.warn("调拨单[{}]状态回读失败：{}", order.getTransferNo(), status.getMessage());
            return;
        }
        String documentStatus = status.getDocumentStatus();
        WmsTransferOrder patch = new WmsTransferOrder();
        patch.setId(order.getId());
        patch.setLastSyncTime(new Date());
        patch.setErpDocumentStatus(documentStatus);
        if (WmsConstants.KINGDEE_DOC_STATUS_APPROVED.equals(documentStatus)) {
            // C：唯一可以置「已完成」的分支
            patch.setSyncStatus(WmsConstants.TRANSFER_SYNC_APPROVED)
                    .setStatus(WmsConstants.TRANSFER_STATUS_COMPLETED)
                    .setErpApproveTime(new Date())
                    .setCompleteTime(new Date())
                    .setLastSyncError(null)
                    .setSyncFailCount(0);
            statusResolver.assertTransition(order.getStatus(), WmsConstants.TRANSFER_STATUS_COMPLETED);
            orderMapper.updateById(patch);
            // 技术债 §5-20：patch.setLastSyncError(null) 因 NOT_NULL 策略不会落库，需显式清空
            clearLastSyncError(order.getId());
            log.info("调拨单[{}]金蝶已审核（C），置为已完成", order.getTransferNo());
            return;
        }
        if ("D".equals(documentStatus)) {
            patch.setSyncStatus(WmsConstants.TRANSFER_SYNC_REJECTED)
                    .setStatus(WmsConstants.TRANSFER_STATUS_EXCEPTION)
                    .setRejectTime(new Date())
                    .setRejectReason(truncate("金蝶调拨单已作废（D），请按业务口径生成反向调拨单冲销"))
                    .setRejectHandleStatus(WmsConstants.TRANSFER_REJECT_PENDING);
            statusResolver.assertTransition(order.getStatus(), WmsConstants.TRANSFER_STATUS_EXCEPTION);
            orderMapper.updateById(patch);
            sendAlert(WmsConstants.TRANSFER_REJECT_ALERT_TITLE,
                    "调拨单 " + order.getTransferNo() + " 已被金蝶作废/驳回，需生成反向调拨单冲销（责任人：仓储主管）");
            return;
        }
        if (WmsConstants.KINGDEE_DOC_STATUS_SAVED.equals(documentStatus)) {
            // A：金蝶侧未提交成功，退回待同步，下一轮重新 Save+Submit
            patch.setSyncStatus(WmsConstants.SYNC_STATUS_PENDING)
                    .setSubmitTime(null)
                    .setLastSyncError("金蝶单据为暂存（A），已退回待同步");
            orderMapper.updateById(patch);
            return;
        }
        // B 或未取到状态：保持待ERP审核，检查审批超时
        orderMapper.updateById(patch);
        checkApprovalTimeout(order);
    }

    private void checkApprovalTimeout(WmsTransferOrder order) {
        int hours = properties.getTransferApprovalTimeoutHours();
        if (hours <= 0 || order.getSubmitTime() == null || order.getApprovalAlertTime() != null) {
            return;
        }
        long waitedHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - order.getSubmitTime().getTime());
        if (waitedHours < hours) {
            return;
        }
        Date now = new Date();
        WmsTransferOrder alertPatch = new WmsTransferOrder();
        alertPatch.setId(order.getId());
        alertPatch.setApprovalAlertTime(now);
        orderMapper.updateById(alertPatch);
        sendAlert(WmsConstants.TRANSFER_APPROVAL_TIMEOUT_ALERT_TITLE,
                "调拨单 " + order.getTransferNo() + " 已提交金蝶并停留审核中（B）超过 " + hours
                        + " 小时，请审批人尽快处理（不自动关单）");
    }

    private void sendAlert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    oConvertUtils.isEmpty(alertToUser) ? WmsConstants.ALERT_TO_USER : alertToUser, title, content));
        } catch (Exception e) {
            log.error("调拨同步告警投递失败：{}", e.getMessage(), e);
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
