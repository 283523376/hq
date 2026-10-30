package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundSyncService;
import org.jeecg.modules.wms.sync.IWmsOutboundSyncGateway;
import org.jeecg.modules.wms.sync.WmsOrderSyncAttempt;
import org.jeecg.modules.wms.sync.WmsOrderSyncResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final WmsOutboundAllocationMapper allocationMapper;
    private final WmsInventoryMapper inventoryMapper;
    private final ISysBaseAPI sysBaseAPI;

    private long[] backoffMs = WmsConstants.SYNC_RETRY_BACKOFF_MS;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsOutboundSyncServiceImpl(IWmsOutboundSyncGateway gateway,
                                      WmsOutboundOrderMapper orderMapper,
                                      WmsOutboundDetailMapper detailMapper,
                                      WmsOutboundAllocationMapper allocationMapper,
                                      WmsInventoryMapper inventoryMapper,
                                      ISysBaseAPI sysBaseAPI) {
        this.gateway = gateway;
        this.orderMapper = orderMapper;
        this.detailMapper = detailMapper;
        this.allocationMapper = allocationMapper;
        this.inventoryMapper = inventoryMapper;
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
        details = expandByAllocation(outboundId, details);
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

    /**
     * 出库明细 → ERP 行：按**分配行**展开，并补齐实际批号。
     *
     * <p>为什么必须展开：销售出库的明细来自发货通知单，批号常常为空（金蝶 FLot 未指定），
     * 而金蝶物料启用批号管理时 Save 会报「批号为空，请录入批号」——批号只有实际扣减的库存行知道。
     * 同一明细可能跨多个批次（FIFO），一行一个批号会写错数量，所以按分配行拆开。
     *
     * <p>没有分配行（历史单/未分配）时原样返回，行为不变。
     */
    private List<WmsOutboundDetail> expandByAllocation(String outboundId, List<WmsOutboundDetail> details) {
        if (details == null || details.isEmpty()) {
            return details;
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", outboundId).orderByAsc("id"));
        if (allocations == null || allocations.isEmpty()) {
            return details;
        }
        Map<String, WmsOutboundAllocation> byDetail = new LinkedHashMap<>();
        for (WmsOutboundAllocation allocation : allocations) {
            byDetail.putIfAbsent(allocation.getDetailId(), allocation);
        }
        List<WmsOutboundDetail> expanded = new ArrayList<>();
        for (WmsOutboundDetail detail : details) {
            boolean split = false;
            for (WmsOutboundAllocation allocation : allocations) {
                if (!detail.getId().equals(allocation.getDetailId())) {
                    continue;
                }
                WmsOutboundDetail line = copyOf(detail);
                line.setBatchNo(resolveAllocationBatch(detail.getBatchNo(), allocation));
                if (allocation.getActualQty() != null && allocation.getActualQty().signum() > 0) {
                    line.setActualQty(allocation.getActualQty());
                }
                expanded.add(line);
                split = true;
            }
            if (!split) {
                // 没有分配行的明细（历史数据）：批号仍为空时不做猜测，交由金蝶报错，避免写错批次
                expanded.add(detail);
            }
        }
        return expanded;
    }

    private WmsOutboundDetail copyOf(WmsOutboundDetail source) {
        WmsOutboundDetail copy = new WmsOutboundDetail();
        copy.setId(source.getId());
        copy.setOutboundId(source.getOutboundId());
        copy.setLineNo(source.getLineNo());
        copy.setMaterialId(source.getMaterialId());
        copy.setMaterialCode(source.getMaterialCode());
        copy.setMaterialName(source.getMaterialName());
        copy.setBatchNo(source.getBatchNo());
        copy.setRequiredQty(source.getRequiredQty());
        copy.setAllocatedQty(source.getAllocatedQty());
        copy.setActualQty(source.getActualQty());
        copy.setUnit(source.getUnit());
        copy.setSourceEntryId(source.getSourceEntryId());
        return copy;
    }

    /** 明细没带批号时，用实际扣减的库存行批号兜底（金蝶按批号出库）。 */
    private String resolveAllocationBatch(String detailBatch, WmsOutboundAllocation allocation) {
        if (oConvertUtils.isNotEmpty(detailBatch)) {
            return detailBatch;
        }
        if (oConvertUtils.isEmpty(allocation.getInventoryId())) {
            return detailBatch;
        }
        WmsInventory inventory = inventoryMapper.selectById(allocation.getInventoryId());
        return inventory == null ? detailBatch : inventory.getBatchNo();
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
