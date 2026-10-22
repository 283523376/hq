package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.service.impl.WmsTransferStatusResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 双时钟状态派生与迁移守卫（CK-004 spec §5.3 / §5.5）。
 *
 * <p>核心断言：「已完成」只出现在「实物完成 + 已审核(C)」这一种组合。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsTransferStatusResolverTest {

    private WmsTransferStatusResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WmsTransferStatusResolver();
    }

    // ---------------- 实物时钟进行中：总状态与 ERP 时钟无关 ----------------

    @Test
    void deriveStatus_pendingExec_returnsPending() {
        assertEquals(WmsConstants.TRANSFER_STATUS_PENDING,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_PENDING, WmsConstants.SYNC_STATUS_PENDING));
    }

    @Test
    void deriveStatus_runningExec_returnsRunning() {
        assertEquals(WmsConstants.TRANSFER_STATUS_RUNNING,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_RUNNING, WmsConstants.SYNC_STATUS_PENDING));
    }

    @Test
    void deriveStatus_inTransitExec_returnsRunning() {
        assertEquals(WmsConstants.TRANSFER_STATUS_RUNNING,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_IN_TRANSIT, WmsConstants.TRANSFER_SYNC_SUBMITTED));
    }

    @Test
    void deriveStatus_pendingInboundExec_returnsRunningEvenIfSyncFailed() {
        assertEquals(WmsConstants.TRANSFER_STATUS_RUNNING,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_PENDING_INBOUND, WmsConstants.SYNC_STATUS_FAILED));
    }

    @Test
    void deriveStatus_exceptionExec_returnsException() {
        assertEquals(WmsConstants.TRANSFER_STATUS_EXCEPTION,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_EXCEPTION, WmsConstants.TRANSFER_SYNC_APPROVED));
    }

    // ---------------- 实物完成：只取决于 ERP 时钟 ----------------

    @Test
    void deriveStatus_completedWithSubmitted_returnsPendingErp() {
        assertEquals(WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.TRANSFER_SYNC_SUBMITTED));
    }

    @Test
    void deriveStatus_completedWithSyncFailed_returnsPendingErp() {
        assertEquals(WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.SYNC_STATUS_FAILED));
    }

    @Test
    void deriveStatus_completedWithSyncing_returnsPendingErp() {
        assertEquals(WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.SYNC_STATUS_RUNNING));
    }

    @Test
    void deriveStatus_completedWithPendingSync_returnsPendingErp() {
        assertEquals(WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.SYNC_STATUS_PENDING));
    }

    @Test
    void deriveStatus_completedWithApproved_returnsCompleted() {
        assertEquals(WmsConstants.TRANSFER_STATUS_COMPLETED,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.TRANSFER_SYNC_APPROVED));
    }

    @Test
    void deriveStatus_completedWithNoSyncRequired_returnsCompleted() {
        assertEquals(WmsConstants.TRANSFER_STATUS_COMPLETED,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.SYNC_STATUS_NOT_REQUIRED));
    }

    @Test
    void deriveStatus_completedWithErpRejected_returnsException() {
        assertEquals(WmsConstants.TRANSFER_STATUS_EXCEPTION,
                resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, WmsConstants.TRANSFER_SYNC_REJECTED));
    }

    @Test
    void deriveStatus_unknownExecStatus_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.deriveStatus("已发货", WmsConstants.SYNC_STATUS_PENDING));
    }

    @Test
    void deriveStatus_unknownSyncWhenCompleted_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_COMPLETED, "半途而废"));
    }

    @Test
    void deriveStatus_nullClock_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.deriveStatus(null, WmsConstants.SYNC_STATUS_PENDING));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.deriveStatus(WmsConstants.TRANSFER_EXEC_PENDING, null));
    }

    // ---------------- 迁移守卫 ----------------

    @Test
    void assertTransition_allowsForwardPath() {
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_PENDING, WmsConstants.TRANSFER_STATUS_RUNNING);
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_RUNNING, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_PENDING_ERP, WmsConstants.TRANSFER_STATUS_COMPLETED);
    }

    @Test
    void assertTransition_allowsExceptionRecoveryAndClose() {
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_RUNNING, WmsConstants.TRANSFER_STATUS_EXCEPTION);
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_EXCEPTION, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_EXCEPTION, WmsConstants.TRANSFER_STATUS_CLOSED);
    }

    @Test
    void assertTransition_sameStatusIsNoop() {
        resolver.assertTransition(WmsConstants.TRANSFER_STATUS_PENDING_ERP, WmsConstants.TRANSFER_STATUS_PENDING_ERP);
    }

    @Test
    void assertTransition_completedIsTerminal() {
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition(
                WmsConstants.TRANSFER_STATUS_COMPLETED, WmsConstants.TRANSFER_STATUS_PENDING_ERP));
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition(
                WmsConstants.TRANSFER_STATUS_COMPLETED, WmsConstants.TRANSFER_STATUS_EXCEPTION));
    }

    @Test
    void assertTransition_closedIsTerminal() {
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition(
                WmsConstants.TRANSFER_STATUS_CLOSED, WmsConstants.TRANSFER_STATUS_RUNNING));
    }

    @Test
    void assertTransition_rejectedCannotReturnToPending() {
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition(
                WmsConstants.TRANSFER_STATUS_EXCEPTION, WmsConstants.TRANSFER_STATUS_PENDING));
    }

    @Test
    void assertTransition_skipStagesRejected() {
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition(
                WmsConstants.TRANSFER_STATUS_PENDING, WmsConstants.TRANSFER_STATUS_COMPLETED));
    }

    @Test
    void assertTransition_unknownStatusThrows() {
        assertThrows(IllegalStateException.class, () -> resolver.assertTransition("飞行中",
                WmsConstants.TRANSFER_STATUS_RUNNING));
    }

    @Test
    void isTerminal_onlyCompletedAndClosed() {
        assertTrue(resolver.isTerminal(WmsConstants.TRANSFER_STATUS_COMPLETED));
        assertTrue(resolver.isTerminal(WmsConstants.TRANSFER_STATUS_CLOSED));
        assertFalse(resolver.isTerminal(WmsConstants.TRANSFER_STATUS_PENDING_ERP));
    }
}
