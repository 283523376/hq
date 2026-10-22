package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * @Description: 调拨单双时钟状态解析器（CK-004 的核心口径，唯一出口）。
 *
 * <p>硬规则（spec §5）：
 * <ol>
 *   <li>单据总状态 {@code status} 只能由 {@link #deriveStatus(String, String)} 派生，任何业务代码禁止直接写；</li>
 *   <li>「已完成」当且仅当 {@code exec_status=实物完成} 且 {@code sync_status=已审核(C)}；</li>
 *   <li>{@code 已完成} / {@code 已关闭} 是终态，迁出即报错；</li>
 *   <li>未知组合直接抛异常，禁止 default 静默兜底（否则错误状态会悄悄落库）。</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Component
public class WmsTransferStatusResolver {

    /** 实物时钟：仍在进行中的三个状态。 */
    private static final Set<String> EXEC_IN_PROGRESS = Set.of(
            WmsConstants.TRANSFER_EXEC_RUNNING,
            WmsConstants.TRANSFER_EXEC_IN_TRANSIT,
            WmsConstants.TRANSFER_EXEC_PENDING_INBOUND);

    /** 实物已完成、等待 ERP 时钟完成的四种同步状态。 */
    private static final Set<String> SYNC_WAITING = Set.of(
            WmsConstants.SYNC_STATUS_PENDING,
            WmsConstants.SYNC_STATUS_RUNNING,
            WmsConstants.SYNC_STATUS_FAILED,
            WmsConstants.TRANSFER_SYNC_SUBMITTED);

    /** 总状态迁移白名单：只有这里列出的迁移允许发生。 */
    private static final Map<String, Set<String>> TRANSITIONS = buildTransitions();

    /**
     * 由两个时钟派生单据总状态（spec §5.3 八行表）。
     *
     * @param execStatus 实物时钟
     * @param syncStatus ERP 时钟
     * @return 单据总状态
     */
    public String deriveStatus(String execStatus, String syncStatus) {
        if (execStatus == null || syncStatus == null) {
            throw new IllegalArgumentException("调拨单双时钟状态不能为空");
        }
        if (WmsConstants.TRANSFER_EXEC_PENDING.equals(execStatus)) {
            return WmsConstants.TRANSFER_STATUS_PENDING;
        }
        if (EXEC_IN_PROGRESS.contains(execStatus)) {
            return WmsConstants.TRANSFER_STATUS_RUNNING;
        }
        if (WmsConstants.TRANSFER_EXEC_EXCEPTION.equals(execStatus)) {
            return WmsConstants.TRANSFER_STATUS_EXCEPTION;
        }
        if (!WmsConstants.TRANSFER_EXEC_COMPLETED.equals(execStatus)) {
            throw new IllegalArgumentException("未知的实物状态：" + execStatus);
        }
        // 实物已完成：只取决于 ERP 时钟
        if (WmsConstants.TRANSFER_SYNC_APPROVED.equals(syncStatus)
                || WmsConstants.SYNC_STATUS_NOT_REQUIRED.equals(syncStatus)) {
            return WmsConstants.TRANSFER_STATUS_COMPLETED;
        }
        if (WmsConstants.TRANSFER_SYNC_REJECTED.equals(syncStatus)) {
            return WmsConstants.TRANSFER_STATUS_EXCEPTION;
        }
        if (SYNC_WAITING.contains(syncStatus)) {
            return WmsConstants.TRANSFER_STATUS_PENDING_ERP;
        }
        throw new IllegalArgumentException("实物完成时不允许的 ERP 状态：" + syncStatus);
    }

    /**
     * 校验总状态迁移是否合法（终态出边为空）。
     *
     * @param from 当前总状态
     * @param to   目标总状态
     */
    public void assertTransition(String from, String to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("调拨单状态不能为空");
        }
        if (from.equals(to)) {
            return;
        }
        Set<String> allowed = TRANSITIONS.get(from);
        if (allowed == null) {
            throw new IllegalStateException("未知的调拨单状态：" + from);
        }
        if (!allowed.contains(to)) {
            throw new IllegalStateException("调拨单状态不允许从「" + from + "」迁移到「" + to + "」");
        }
    }

    /**
     * 是否为终态（已完成 / 已关闭），终态不接受任何推进。
     */
    public boolean isTerminal(String status) {
        return WmsConstants.TRANSFER_STATUS_COMPLETED.equals(status)
                || WmsConstants.TRANSFER_STATUS_CLOSED.equals(status);
    }

    private static Map<String, Set<String>> buildTransitions() {
        Map<String, Set<String>> map = new java.util.HashMap<>();
        map.put(WmsConstants.TRANSFER_STATUS_PENDING, Set.of(
                WmsConstants.TRANSFER_STATUS_RUNNING,
                WmsConstants.TRANSFER_STATUS_EXCEPTION,
                WmsConstants.TRANSFER_STATUS_CLOSED));
        map.put(WmsConstants.TRANSFER_STATUS_RUNNING, Set.of(
                WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                WmsConstants.TRANSFER_STATUS_EXCEPTION,
                WmsConstants.TRANSFER_STATUS_CLOSED));
        map.put(WmsConstants.TRANSFER_STATUS_PENDING_ERP, Set.of(
                WmsConstants.TRANSFER_STATUS_COMPLETED,
                WmsConstants.TRANSFER_STATUS_EXCEPTION,
                WmsConstants.TRANSFER_STATUS_CLOSED));
        // 异常可回到待ERP审核（驳回后金蝶重新审批通过）或人工关闭；不得回到待执行
        map.put(WmsConstants.TRANSFER_STATUS_EXCEPTION, Set.of(
                WmsConstants.TRANSFER_STATUS_PENDING_ERP,
                WmsConstants.TRANSFER_STATUS_CLOSED));
        map.put(WmsConstants.TRANSFER_STATUS_COMPLETED, Set.of());
        map.put(WmsConstants.TRANSFER_STATUS_CLOSED, Set.of());
        return map;
    }
}
