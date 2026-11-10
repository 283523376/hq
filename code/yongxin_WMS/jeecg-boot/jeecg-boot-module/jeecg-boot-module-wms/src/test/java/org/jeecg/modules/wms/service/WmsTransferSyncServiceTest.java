package org.jeecg.modules.wms.service;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.mapper.WmsTransferDetailMapper;
import org.jeecg.modules.wms.mapper.WmsTransferOrderMapper;
import org.jeecg.modules.wms.service.impl.WmsTransferStatusResolver;
import org.jeecg.modules.wms.service.impl.WmsTransferSyncServiceImpl;
import org.jeecg.modules.wms.sync.IWmsKingdeeTransferWriter;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeTransferStatus;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 调拨 ERP 同步：提交、C/D/A/B 四分支、审批超时告警、失败重试。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsTransferSyncServiceTest {

    private WmsTransferOrderMapper orderMapper;
    private WmsTransferDetailMapper detailMapper;
    private IWmsKingdeeTransferWriter writer;
    private WmsKingdeeProperties properties;
    private ISysBaseAPI sysBaseAPI;
    private WmsTransferSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsTransferOrderMapper.class);
        detailMapper = mock(WmsTransferDetailMapper.class);
        writer = mock(IWmsKingdeeTransferWriter.class);
        properties = new WmsKingdeeProperties();
        properties.setTransferApprovalTimeoutHours(24);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsTransferSyncServiceImpl(orderMapper, detailMapper, writer, properties,
                new WmsTransferStatusResolver(), sysBaseAPI);
        ReflectionTestUtils.setField(service, "alertToUser", "admin");
    }

    private WmsTransferOrder order(String syncStatus, String status) {
        WmsTransferOrder order = new WmsTransferOrder();
        order.setId("1");
        order.setTransferNo("DB1001");
        order.setErpBillNo("DB1001");
        order.setExecStatus(WmsConstants.TRANSFER_EXEC_COMPLETED);
        order.setSyncStatus(syncStatus);
        order.setStatus(status);
        order.setRetryCount(0);
        order.setSyncFailCount(0);
        return order;
    }

    /** 捕获写库的 patch，断言 sync_status / status。 */
    private WmsTransferOrder capturedPatch() {
        ArgumentCaptor<WmsTransferOrder> captor = ArgumentCaptor.forClass(WmsTransferOrder.class);
        verify(orderMapper).updateById(captor.capture());
        return captor.getValue();
    }

    // ---------------- 提交 ----------------

    @Test
    void submitPending_realSuccess_marksSubmitted() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.SYNC_STATUS_PENDING, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(writer.saveAndSubmit(any(), any())).thenReturn(WmsOrderSyncResult.ok());

        assertEquals(1, service.submitPending());
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.TRANSFER_SYNC_SUBMITTED, patch.getSyncStatus());
        assertEquals(WmsConstants.KINGDEE_DOC_STATUS_SUBMITTED, patch.getErpDocumentStatus());
        // 2026-10-28「ERP 单号为准」：erp_bill_no 由 writer 用金蝶返回单号回存，服务层不再拿 WMS 单号占位
        assertEquals(null, patch.getErpBillNo());
    }

    @Test
    void submitPending_mocked_stillMarksSubmittedWithNotice() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.SYNC_STATUS_PENDING, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(writer.saveAndSubmit(any(), any())).thenReturn(WmsOrderSyncResult.okMock());

        service.submitPending();
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.TRANSFER_SYNC_SUBMITTED, patch.getSyncStatus());
        assertEquals("金蝶未启用（模拟提交）", patch.getLastSyncError());
    }

    @Test
    void submitPending_failureIncrementsCounters() {
        WmsTransferOrder existing = order(WmsConstants.SYNC_STATUS_PENDING, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        existing.setSyncFailCount(1);
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(writer.saveAndSubmit(any(), any())).thenReturn(WmsOrderSyncResult.fail("仓管员必填"));

        service.submitPending();
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.SYNC_STATUS_FAILED, patch.getSyncStatus());
        assertEquals(1, patch.getRetryCount());
        assertEquals(2, patch.getSyncFailCount());
        assertEquals("仓管员必填", patch.getLastSyncError());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void submitPending_thirdFailureSendsAlert() {
        WmsTransferOrder existing = order(WmsConstants.SYNC_STATUS_FAILED, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        existing.setSyncFailCount(2);
        existing.setRetryCount(2);
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));
        when(detailMapper.selectList(any())).thenReturn(List.of());
        when(writer.saveAndSubmit(any(), any())).thenReturn(WmsOrderSyncResult.fail("金蝶超时"));

        service.submitPending();
        verify(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));
    }

    // ---------------- 回读 ----------------

    @Test
    void pollStatus_approved_movesToCompleted() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("C"));

        service.pollStatus();
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.TRANSFER_SYNC_APPROVED, patch.getSyncStatus());
        assertEquals(WmsConstants.TRANSFER_STATUS_COMPLETED, patch.getStatus());
        assertEquals("C", patch.getErpDocumentStatus());
    }

    @Test
    void pollStatus_rejected_movesToExceptionAndAlerts() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("D"));

        service.pollStatus();
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.TRANSFER_SYNC_REJECTED, patch.getSyncStatus());
        assertEquals(WmsConstants.TRANSFER_STATUS_EXCEPTION, patch.getStatus());
        assertEquals(WmsConstants.TRANSFER_REJECT_PENDING, patch.getRejectHandleStatus());
        verify(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void pollStatus_draftReturnsToPending() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("A"));

        service.pollStatus();
        WmsTransferOrder patch = capturedPatch();
        assertEquals(WmsConstants.SYNC_STATUS_PENDING, patch.getSyncStatus());
        assertEquals("A", patch.getErpDocumentStatus());
    }

    @Test
    void pollStatus_submittedBeyondTimeout_alertsOnce() {
        WmsTransferOrder existing = order(WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        existing.setSubmitTime(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(30)));
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("B"));

        service.pollStatus();
        verify(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));
        org.mockito.Mockito.verify(orderMapper, org.mockito.Mockito.atLeast(2)).updateById(any(WmsTransferOrder.class));
    }

    @Test
    void pollStatus_submittedWithinTimeout_noAlert() {
        WmsTransferOrder existing = order(WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        existing.setSubmitTime(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)));
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("B"));

        service.pollStatus();
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void pollStatus_alreadyAlerted_doesNotRepeat() {
        WmsTransferOrder existing = order(WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        existing.setSubmitTime(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(30)));
        existing.setApprovalAlertTime(new Date());
        when(orderMapper.selectList(any())).thenReturn(List.of(existing));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.ok("B"));

        service.pollStatus();
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void pollStatus_queryFailure_recordsErrorOnly() {
        when(orderMapper.selectList(any())).thenReturn(List.of(order(
                WmsConstants.TRANSFER_SYNC_SUBMITTED, WmsConstants.TRANSFER_STATUS_PENDING_ERP)));
        when(writer.queryStatus(anyString())).thenReturn(WmsKingdeeTransferStatus.fail("金蝶不可达"));

        service.pollStatus();
        WmsTransferOrder patch = capturedPatch();
        assertEquals("金蝶不可达", patch.getLastSyncError());
        assertEquals(null, patch.getSyncStatus());
    }
}
