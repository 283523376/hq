package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundConfirmDTO;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundReviewDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSalesCreateDTO;
import org.jeecg.modules.wms.dto.WmsOutboundSampleCreateDTO;
import org.jeecg.modules.wms.dto.WmsSalesOrderSyncDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrderDetail;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundAllocator;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.service.IWmsSalesOrderService;
import org.jeecg.modules.wms.vo.WmsSalesOrderVO;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsOutboundConfirmVO;
import org.jeecg.modules.wms.vo.WmsOutboundDetailVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 出库单创建与查询。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsOutboundServiceImpl extends ServiceImpl<WmsOutboundOrderMapper, WmsOutboundOrder>
        implements IWmsOutboundService {

    @Autowired
    private WmsOutboundDetailMapper detailMapper;

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper allocationMapper;

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsInventoryMapper inventoryMapper;

    /** 盘点重盘标记（可空：盘点模块可能尚未参与）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.jeecg.modules.wms.service.IWmsStocktakeService stocktakeService;

    @Autowired
    private org.jeecg.modules.wms.mapper.WmsLocationMapper locationMapper;

    @Autowired
    private WmsWorkOrderMapper workOrderMapper;

    @Autowired
    private WmsMaterialMapper materialMapper;

    @Autowired
    private org.jeecg.modules.wms.sync.IWmsKingdeePpbomReader ppbomReader;

    @Autowired
    private IWmsSalesOrderService salesOrderService;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsKingdeeWorkOrderSyncService kingdeeWorkOrderSyncService;

    @Autowired
    private IWmsOutboundAllocator allocator;

    @Autowired
    private org.jeecg.modules.wms.service.IWmsOutboundTaskService taskService;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Override
    public void dispatch(String id) {
        taskService.dispatch(id);
    }

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    @Override
    public void allocate(String id) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        boolean allowed = WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE.equals(order.getStatus())
                || WmsConstants.OUTBOUND_STATUS_EXCEPTION.equals(order.getStatus());
        if (!allowed) {
            throw new RuntimeException("当前状态不允许分配");
        }
        List<WmsOutboundDetail> details = detailMapper.selectList(Wrappers.<WmsOutboundDetail>query()
                .eq("outbound_id", id).orderByAsc("line_no"));
        if (details == null || details.isEmpty()) {
            throw new RuntimeException("出库明细为空");
        }
        // 并发闸门：先条件认领状态，双击/多人同时点「分配」只有一个能进来
        int claimed = baseMapper.update(null, new UpdateWrapper<WmsOutboundOrder>()
                .set("status", WmsConstants.OUTBOUND_STATUS_ALLOCATED)
                .set("update_time", new Date())
                .eq("id", id)
                .in("status", WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE,
                        WmsConstants.OUTBOUND_STATUS_EXCEPTION));
        if (claimed == 0) {
            throw new RuntimeException("单据正在分配或状态已变化，请刷新后重试");
        }
        try {
            cleanupPreviousAllocation(id, details);
            allocator.allocate(order, details);
            log.info("出库单[{}]分配完成：明细={}", order.getOutboundNo(), details.size());
        } catch (RuntimeException e) {
            String message = truncate(e.getMessage());
            updateStatus(id, WmsConstants.OUTBOUND_STATUS_EXCEPTION, message);
            String title = message != null && message.contains("库位")
                    ? WmsConstants.OUTBOUND_LOCATION_ALERT_TITLE : WmsConstants.OUTBOUND_STOCK_ALERT_TITLE;
            alert(title, "出库单：" + order.getOutboundNo() + "；原因：" + message);
            throw e;
        }
    }

    /**
     * 「异常待人工」重新分配前清掉上一次的分配与锁定，否则会在同一库存行上锁两遍、确认时双倍扣减。
     */
    private void cleanupPreviousAllocation(String outboundId, List<WmsOutboundDetail> details) {
        List<WmsOutboundAllocation> old = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", outboundId));
        if (old != null && !old.isEmpty()) {
            for (WmsOutboundAllocation allocation : old) {
                if (WmsConstants.TASK_STATUS_ACCEPTED.equals(allocation.getTaskStatus())
                        || WmsConstants.TASK_STATUS_EXECUTING.equals(allocation.getTaskStatus())) {
                    throw new RuntimeException("存在执行中的设备任务，请等待任务结束或先关单");
                }
                if (allocation.getAllocatedQty() != null && allocation.getAllocatedQty().signum() > 0) {
                    inventoryMapper.releaseLocked(allocation.getInventoryId(), allocation.getAllocatedQty());
                }
                releaseLocationIfEmpty(allocation.getLocationId());
            }
            allocationMapper.delete(Wrappers.<WmsOutboundAllocation>query()
                    .eq("outbound_id", outboundId));
            log.info("出库单[{}]重新分配：已清理旧分配行 {} 条", outboundId, old.size());
        }
        for (WmsOutboundDetail detail : details) {
            detail.setAllocatedQty(BigDecimal.ZERO);
            detail.setActualQty(BigDecimal.ZERO);
            detail.setUpdateTime(new Date());
            detailMapper.updateById(detail);
        }
    }

    @Override
    public boolean isOutboundTask(String taskNo) {
        return oConvertUtils.isNotEmpty(taskNo)
                && allocationMapper.selectByTaskNo(taskNo.trim()) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean onTaskFeedback(String taskNo, String containerCode, String taskStatus,
                                  String message, boolean rms) {
        if (oConvertUtils.isEmpty(taskNo)) {
            return false;
        }
        String status = taskStatus == null ? "" : taskStatus.trim().toUpperCase();
        WmsOutboundAllocation allocation = allocationMapper.selectByTaskNo(taskNo.trim());
        if (allocation == null) {
            return false;
        }
        if ("FINISHED".equals(status)) {
            if (allocationMapper.claimFinished(taskNo.trim()) == 0) {
                log.info("出库任务[{}]重复回执或已处理，忽略", taskNo);
                return false;
            }
            WmsOutboundOrder order = getById(allocation.getOutboundId());
            if (order == null) {
                return false;
            }
            // 在途任务落账时，若库位正被盘点冻结，把对应明细标重盘（spec §5.3）
            try {
                WmsOutboundDetail feedbackDetail = detailMapper.selectById(allocation.getDetailId());
                stocktakeService.markRecheck(allocation.getLocationId(),
                        feedbackDetail == null ? null : feedbackDetail.getMaterialId(),
                        feedbackDetail == null ? null : feedbackDetail.getBatchNo(),
                        allocation.getContainerCode());
            } catch (Exception e) {
                log.warn("重盘标记失败（不影响出库回执）：{}", e.getMessage());
            }
            List<WmsOutboundAllocation> all = allocationMapper.selectList(
                    Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", order.getId()));
            boolean allFinished = all != null && !all.isEmpty();
            if (all != null) {
                for (WmsOutboundAllocation item : all) {
                    if (!WmsConstants.TASK_STATUS_FINISHED.equals(item.getTaskStatus())) {
                        allFinished = false;
                        break;
                    }
                }
            }
            patchOrderStatusFrom(order.getId(),
                    allFinished ? WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW
                            : WmsConstants.OUTBOUND_STATUS_EXECUTING, null,
                    WmsConstants.OUTBOUND_STATUS_ALLOCATED,
                    WmsConstants.OUTBOUND_STATUS_EXECUTING,
                    WmsConstants.OUTBOUND_STATUS_EXCEPTION,
                    WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
            log.info("出库任务完成：单号={}，任务号={}，来源={}，全部完成={}",
                    order.getOutboundNo(), taskNo, rms ? "RMS" : "WCS", allFinished);
            return allFinished;
        }
        if ("RUNNING".equals(status) || "OUTSTATION".equals(status)) {
            allocationMapper.markRunning(taskNo.trim());
            patchOrderStatusFrom(allocation.getOutboundId(), WmsConstants.OUTBOUND_STATUS_EXECUTING, null,
                    WmsConstants.OUTBOUND_STATUS_ALLOCATED,
                    WmsConstants.OUTBOUND_STATUS_EXECUTING,
                    WmsConstants.OUTBOUND_STATUS_EXCEPTION);
            return false;
        }
        if ("FAILED".equals(status)) {
            String error = oConvertUtils.isEmpty(message) ? "设备回执任务失败" : message;
            allocationMapper.markFailed(taskNo.trim(), truncate(error));
            WmsOutboundOrder order = getById(allocation.getOutboundId());
            if (order != null) {
                boolean moved = patchOrderStatusFrom(order.getId(), WmsConstants.OUTBOUND_STATUS_EXCEPTION,
                        truncate(error),
                        WmsConstants.OUTBOUND_STATUS_ALLOCATED,
                        WmsConstants.OUTBOUND_STATUS_EXECUTING,
                        WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
                if (moved) {
                    alert(WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE,
                            "出库单：" + order.getOutboundNo() + "；任务号：" + taskNo + "；原因：" + error);
                }
            }
            return false;
        }
        log.warn("出库任务[{}]回执状态未知：{}", taskNo, taskStatus);
        return false;
    }

    @Override
    public void review(String id, WmsOutboundReviewDTO dto) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        String type = order.getOutboundType();
        if (!WmsConstants.OUTBOUND_TYPE_SALES.equals(type)
                && !WmsConstants.OUTBOUND_TYPE_SAMPLE.equals(type)) {
            throw new RuntimeException("仅销售出库与样品出库支持复核");
        }
        boolean allowed = WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW.equals(order.getStatus())
                || WmsConstants.OUTBOUND_STATUS_REVIEW_EXCEPTION.equals(order.getStatus());
        if (!allowed) {
            throw new RuntimeException("当前状态不允许复核");
        }
        if (dto == null || dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("复核明细不能为空");
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id));
        Map<String, WmsOutboundAllocation> allocationMap = new LinkedHashMap<>();
        if (allocations != null) {
            for (WmsOutboundAllocation allocation : allocations) {
                allocationMap.put(allocation.getId(), allocation);
            }
        }
        Map<String, WmsOutboundDetail> detailMap = detailMap(id);
        List<WmsOutboundAllocation> mismatched = new ArrayList<>();
        for (WmsOutboundReviewDTO.Item item : dto.getDetails()) {
            WmsOutboundAllocation allocation = allocationMap.get(item.getAllocationId());
            if (allocation == null) {
                throw new RuntimeException("分配明细不存在：" + item.getAllocationId());
            }
            BigDecimal actual = item.getActualQty() == null
                    ? nvl(allocation.getAllocatedQty()) : item.getActualQty();
            if (actual.signum() < 0 || actual.compareTo(nvl(allocation.getAllocatedQty())) > 0) {
                throw new RuntimeException("实收数量不能大于分配数量");
            }
            WmsOutboundDetail detail = detailMap.get(allocation.getDetailId());
            boolean matched = scanMatches(item.getScanCode(), detail, allocation);
            allocation.setActualQty(actual);
            allocation.setScanCode(item.getScanCode());
            allocation.setReviewStatus(matched
                    ? WmsConstants.REVIEW_STATUS_MATCHED : WmsConstants.REVIEW_STATUS_MISMATCHED);
            allocation.setReviewNote(matched ? null : "扫描码与物料/料箱不匹配");
            updateAllocationReview(allocation);
            if (!matched) {
                mismatched.add(allocation);
            }
        }
        refreshDetailActual(allocations, detailMap);

        if (!mismatched.isEmpty() && !dto.isReleaseMismatch()) {
            patchOrderStatus(id, WmsConstants.OUTBOUND_STATUS_REVIEW_EXCEPTION, "复核不一致");
            alert(WmsConstants.OUTBOUND_REVIEW_ALERT_TITLE,
                    "出库单：" + order.getOutboundNo() + "；不一致行：" + mismatched.size()
                            + "；请改数量/重扫或填写原因放行");
            throw new RuntimeException("复核不一致，请改数量/重扫或填写原因放行");
        }
        if (!mismatched.isEmpty()) {
            if (oConvertUtils.isEmpty(dto.getReleaseReason())) {
                throw new RuntimeException("放行原因不能为空");
            }
            for (WmsOutboundAllocation allocation : mismatched) {
                allocation.setReviewStatus(WmsConstants.REVIEW_STATUS_RELEASED);
                allocation.setReviewNote(truncate(dto.getReleaseReason()));
                updateAllocationReview(allocation);
            }
        }
        patchOrderStatus(id, WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW, null);
        log.info("出库单[{}]复核完成：不一致={}，放行={}", order.getOutboundNo(), mismatched.size(),
                dto.isReleaseMismatch());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundConfirmVO confirmPick(String id, WmsOutboundConfirmDTO dto) {
        return doConfirm(id, dto, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundConfirmVO confirm(String id, WmsOutboundConfirmDTO dto) {
        return doConfirm(id, dto, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(String id, String reason) {
        if (oConvertUtils.isEmpty(reason == null ? null : reason.trim())) {
            throw new RuntimeException("关单原因不能为空");
        }
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        if (WmsConstants.OUTBOUND_STATUS_COMPLETED.equals(order.getStatus())
                || WmsConstants.OUTBOUND_STATUS_CLOSED.equals(order.getStatus())) {
            throw new RuntimeException("当前状态不允许关单");
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id));
        if (allocations != null) {
            for (WmsOutboundAllocation allocation : allocations) {
                // 关单一定释放锁定与库位：即使任务已完成但尚未确认出库，锁定也还挂着；
                // 若货已出库，这里只会释放锁定（库存数量未扣），需要人工盘点，故单独告警提示。
                if (WmsConstants.TASK_STATUS_FINISHED.equals(allocation.getTaskStatus())) {
                    log.warn("出库单[{}]关单时任务[{}]已完成但未确认出库，货物可能已离库，请人工核对库存",
                            order.getOutboundNo(), allocation.getTaskNo());
                }
                if (allocation.getAllocatedQty() != null && allocation.getAllocatedQty().signum() > 0) {
                    inventoryMapper.releaseLocked(allocation.getInventoryId(), allocation.getAllocatedQty());
                }
                releaseLocationIfEmpty(allocation.getLocationId());
                if (!WmsConstants.TASK_STATUS_FINISHED.equals(allocation.getTaskStatus())
                        && !WmsConstants.TASK_STATUS_CLOSED.equals(allocation.getTaskStatus())) {
                    allocationMapper.update(null, new UpdateWrapper<WmsOutboundAllocation>()
                            .set("task_status", WmsConstants.TASK_STATUS_CLOSED)
                            .set("update_time", new Date())
                            .eq("id", allocation.getId()));
                }
            }
        }
        WmsOutboundOrder patch = new WmsOutboundOrder()
                .setStatus(WmsConstants.OUTBOUND_STATUS_CLOSED)
                .setSyncStatus(WmsConstants.SYNC_STATUS_NOT_REQUIRED)
                .setCloseReason(reason.trim())
                .setCloseBy(WmsUtils.currentUsername());
        patch.setUpdateTime(new Date());
        patch.setCloseTime(new Date());
        baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>().eq("id", id));
        log.info("出库单[{}]关单：原因={}", order.getOutboundNo(), reason.trim());
    }

    @Override
    public WmsOutboundDetailVO detail(String id) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        WmsOutboundDetailVO vo = new WmsOutboundDetailVO();
        vo.setOrder(order);
        List<WmsOutboundDetail> details = detailMapper.selectList(Wrappers.<WmsOutboundDetail>query()
                .eq("outbound_id", id).orderByAsc("line_no"));
        vo.setDetails(details == null ? List.of() : details);
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id).orderByAsc("id"));
        vo.setAllocations(allocations == null ? List.of() : allocations);
        return vo;
    }

    /** 确认出库/取料确认共用：校验、扣减、差额释放、状态回写。 */
    private WmsOutboundConfirmVO doConfirm(String id, WmsOutboundConfirmDTO dto, boolean pick) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        if (!WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW.equals(order.getStatus())) {
            throw new RuntimeException("当前状态不允许确认出库");
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id));
        if (allocations == null || allocations.isEmpty()) {
            throw new RuntimeException("出库分配明细为空");
        }
        if (!pick) {
            for (WmsOutboundAllocation allocation : allocations) {
                if (!WmsConstants.REVIEW_STATUS_MATCHED.equals(allocation.getReviewStatus())
                        && !WmsConstants.REVIEW_STATUS_RELEASED.equals(allocation.getReviewStatus())) {
                    throw new RuntimeException("请先完成复核");
                }
            }
            // CK-002 方向反转后不再有「销售订单未同步」门槛（销售订单由 ERP → WMS 只读）
        }
        Set<String> specified = applyConfirmQty(allocations, dto);
        // 未复核且未传实收数量时按分配数量处理：分配时 actual_qty 只是占位 0，
        // 若照 0 处理则库存不扣减却把单据标记完成（E2E 实测 quantity 8→8）。
        for (WmsOutboundAllocation allocation : allocations) {
            if (!specified.contains(allocation.getId())
                    && WmsConstants.REVIEW_STATUS_PENDING.equals(allocation.getReviewStatus())) {
                allocation.setActualQty(nvl(allocation.getAllocatedQty()));
            }
        }
        Map<String, BigDecimal> detailActual = new LinkedHashMap<>();
        for (WmsOutboundAllocation allocation : allocations) {
            BigDecimal allocated = nvl(allocation.getAllocatedQty());
            BigDecimal actual = nvl(allocation.getActualQty());
            if (actual.signum() < 0) {
                throw new RuntimeException("实收数量不能为负数");
            }
            if (actual.compareTo(allocated) > 0) {
                throw new RuntimeException("实收数量不能大于分配数量");
            }
            if (actual.signum() > 0 && inventoryMapper.deductLocked(
                    allocation.getInventoryId(), actual) == 0) {
                patchOrderStatus(id, WmsConstants.OUTBOUND_STATUS_EXCEPTION, "库存扣减失败");
                alert(WmsConstants.OUTBOUND_STOCK_ALERT_TITLE,
                        "出库单：" + order.getOutboundNo() + "；库存扣减失败，库存已变化");
                throw new RuntimeException("库存扣减失败，库存已变化");
            }
            BigDecimal diff = allocated.subtract(actual);
            if (diff.signum() > 0) {
                inventoryMapper.releaseLocked(allocation.getInventoryId(), diff);
            }
            if (pick) {
                releaseLocationIfEmpty(allocation.getLocationId());
            }
            // 回写确认后的实收数量：复核可能没经过（CK-001），分配行上仍是占位 0
            allocationMapper.update(null, new UpdateWrapper<WmsOutboundAllocation>()
                    .set("actual_qty", actual)
                    .set("update_time", new Date())
                    .eq("id", allocation.getId()));
            detailActual.merge(allocation.getDetailId(), actual, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : detailActual.entrySet()) {
            WmsOutboundDetail detail = detailMapper.selectById(entry.getKey());
            if (detail != null) {
                detail.setActualQty(entry.getValue());
                detail.setUpdateTime(new Date());
                detailMapper.updateById(detail);
            }
        }
        // 只有真有回写实现的类型才置「待同步」；样品（E5 未定）与外协（E6 未定）置「无需同步」，
        // 否则重试任务会每 5 分钟对着一个永远不会成功的回写刷失败。
        boolean needsErpSync = WmsConstants.OUTBOUND_TYPE_PICK.equals(order.getOutboundType())
                || WmsConstants.OUTBOUND_TYPE_SALES.equals(order.getOutboundType());
        WmsOutboundOrder patch = new WmsOutboundOrder()
                .setStatus(WmsConstants.OUTBOUND_STATUS_COMPLETED)
                .setSyncStatus(needsErpSync
                        ? WmsConstants.SYNC_STATUS_PENDING : WmsConstants.SYNC_STATUS_NOT_REQUIRED);
        patch.setUpdateTime(new Date());
        patch.setCompleteTime(new Date());
        baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>().eq("id", id));
        if (WmsConstants.OUTBOUND_TYPE_SALES.equals(order.getOutboundType())
                && oConvertUtils.isNotEmpty(order.getSalesOrderNo())) {
            // 刷新销售订单快照的已出库数量，供下次出库校验剩余量
            salesOrderService.refreshShippedQty(order.getSalesOrderNo());
        }
        log.info("出库单[{}]确认完成：类型={}", order.getOutboundNo(), order.getOutboundType());
        return WmsOutboundConfirmVO.ok(order.getOutboundNo());
    }

    /** 页面改数量：确认时以页面实收为准。 */
    /**
     * 覆盖实收数量，返回调用方显式指定过的分配明细 ID。
     */
    private Set<String> applyConfirmQty(List<WmsOutboundAllocation> allocations, WmsOutboundConfirmDTO dto) {
        Set<String> specified = new HashSet<>();
        if (dto == null || dto.getDetails() == null || dto.getDetails().isEmpty()) {
            return specified;
        }
        Map<String, WmsOutboundAllocation> map = new LinkedHashMap<>();
        for (WmsOutboundAllocation allocation : allocations) {
            map.put(allocation.getId(), allocation);
        }
        for (WmsOutboundConfirmDTO.Item item : dto.getDetails()) {
            WmsOutboundAllocation allocation = map.get(item.getAllocationId());
            if (allocation == null) {
                throw new RuntimeException("分配明细不存在：" + item.getAllocationId());
            }
            if (item.getActualQty() != null) {
                if (item.getActualQty().signum() < 0) {
                    throw new RuntimeException("实收数量不能为负数");
                }
                allocation.setActualQty(item.getActualQty());
                specified.add(allocation.getId());
            }
        }
        return specified;
    }

    private Map<String, WmsOutboundDetail> detailMap(String outboundId) {
        Map<String, WmsOutboundDetail> map = new LinkedHashMap<>();
        List<WmsOutboundDetail> details = detailMapper.selectList(Wrappers.<WmsOutboundDetail>query()
                .eq("outbound_id", outboundId));
        if (details != null) {
            for (WmsOutboundDetail detail : details) {
                map.put(detail.getId(), detail);
            }
        }
        return map;
    }

    /** 扫描码等于物料编码或料箱编码即视为一致。 */
    private boolean scanMatches(String scanCode, WmsOutboundDetail detail, WmsOutboundAllocation allocation) {
        if (oConvertUtils.isEmpty(scanCode)) {
            return false;
        }
        String code = scanCode.trim();
        return code.equalsIgnoreCase(detail == null ? null : detail.getMaterialCode())
                || code.equalsIgnoreCase(allocation.getContainerCode());
    }

    private void updateAllocationReview(WmsOutboundAllocation allocation) {
        allocationMapper.update(null, new UpdateWrapper<WmsOutboundAllocation>()
                .set("review_status", allocation.getReviewStatus())
                .set("scan_code", allocation.getScanCode())
                .set("actual_qty", nvl(allocation.getActualQty()))
                .set("review_note", truncate(allocation.getReviewNote()))
                .set("update_time", new Date())
                .eq("id", allocation.getId()));
    }

    private void refreshDetailActual(List<WmsOutboundAllocation> allocations,
                                     Map<String, WmsOutboundDetail> detailMap) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (WmsOutboundAllocation allocation : allocations) {
            sums.merge(allocation.getDetailId(), nvl(allocation.getActualQty()), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : sums.entrySet()) {
            WmsOutboundDetail detail = detailMap.get(entry.getKey());
            if (detail != null) {
                detail.setActualQty(entry.getValue());
                detail.setUpdateTime(new Date());
                detailMapper.updateById(detail);
            }
        }
    }

    /** 立库库位无剩余库存且无锁定时释放为空闲。 */
    private void releaseLocationIfEmpty(String locationId) {
        if (oConvertUtils.isEmpty(locationId)) {
            return;
        }
        WmsInventory remaining = inventoryMapper.selectOne(Wrappers.<WmsInventory>query()
                .eq("location_id", locationId)
                .apply("quantity > 0 OR locked_qty > 0")
                .last("LIMIT 1"));
        if (remaining != null) {
            return;
        }
        locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                .set("status", WmsConstants.LOCATION_STATUS_IDLE)
                .set("update_time", new Date())
                .eq("id", locationId)
                .eq("status", WmsConstants.LOCATION_STATUS_OCCUPIED));
    }

    private void patchOrderStatus(String id, String status, String lastSyncError) {
        WmsOutboundOrder patch = new WmsOutboundOrder().setStatus(status).setLastSyncError(lastSyncError);
        patch.setUpdateTime(new Date());
        baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>().eq("id", id));
    }

    /**
     * 设备回执回写状态：只允许从白名单状态迁移，迟到的 RUNNING/FAILED 不能把已完成/已关闭的单据拉回。
     */
    private boolean patchOrderStatusFrom(String id, String status, String lastSyncError, String... allowedFrom) {
        WmsOutboundOrder patch = new WmsOutboundOrder().setStatus(status).setLastSyncError(lastSyncError);
        patch.setUpdateTime(new Date());
        int rows = baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>()
                .eq("id", id)
                .in("status", allowedFrom));
        if (rows == 0) {
            log.info("出库单[{}]当前状态不在 {} 内，忽略回执状态 {}", id,
                    String.join("/", allowedFrom), status);
        }
        return rows > 0;
    }

    /** 条件回写状态：只有仍处于分配中/异常状态的行才更新，避免覆盖并发结果。 */
    private void updateStatus(String id, String status, String lastSyncError) {
        WmsOutboundOrder patch = new WmsOutboundOrder().setStatus(status).setLastSyncError(lastSyncError);
        patch.setUpdateTime(new Date());
        baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>()
                .eq("id", id)
                .in("status", WmsConstants.OUTBOUND_STATUS_ALLOCATED,
                        WmsConstants.OUTBOUND_STATUS_EXCEPTION));
    }

    private void alert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    alertToUser == null ? WmsConstants.ALERT_TO_USER : alertToUser, title, content));
        } catch (Exception e) {
            log.error("出库告警投递失败：{}", e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    @Override
    public org.jeecg.modules.wms.vo.WmsOutboundScanVO scanPick(String workOrderNo) {
        if (oConvertUtils.isEmpty(workOrderNo)) {
            throw new RuntimeException("工单号不能为空");
        }
        WmsWorkOrder workOrder = workOrderMapper.selectOne(Wrappers.<WmsWorkOrder>query()
                .eq("work_order_no", workOrderNo.trim()).last("LIMIT 1"));
        if (workOrder == null
                && oConvertUtils.isNotEmpty(workOrderNo)
                && kingdeeWorkOrderSyncService.syncSingleFromKingdee(workOrderNo.trim())) {
            workOrder = workOrderMapper.selectOne(Wrappers.<WmsWorkOrder>query()
                    .eq("work_order_no", workOrderNo.trim()).last("LIMIT 1"));
        }
        if (workOrder == null) {
            throw new RuntimeException("工单号无效");
        }
        org.jeecg.modules.wms.vo.WmsOutboundScanVO vo = new org.jeecg.modules.wms.vo.WmsOutboundScanVO();
        vo.setWorkOrderNo(workOrder.getWorkOrderNo());
        vo.setMaterialCode(workOrder.getMaterialCode());
        vo.setMaterialName(workOrder.getMaterialName());
        vo.setProductType(workOrder.getProductType());
        vo.setPlanQty(workOrder.getPlanQty());
        vo.setBatchNo(workOrder.getBatchNo());
        vo.setWorkOrderStatus(workOrder.getWorkOrderStatus());
        List<org.jeecg.modules.wms.vo.WmsOutboundRequirementVO> requirements =
                ppbomReader.read(workOrder.getWorkOrderNo());
        if (requirements == null || requirements.isEmpty()) {
            vo.setRequirementSource(WmsConstants.REQUIREMENT_SOURCE_MANUAL);
            vo.setWarning("未从金蝶获取到生产用料清单，请手工录入领料明细");
        } else {
            vo.setRequirementSource(WmsConstants.REQUIREMENT_SOURCE_PPBOM);
            vo.setDetails(requirements);
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundOrder createPick(WmsOutboundPickCreateDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWorkOrderNo())) {
            throw new RuntimeException("工单号不能为空");
        }
        WmsWorkOrder workOrder = workOrderMapper.selectOne(Wrappers.<WmsWorkOrder>query()
                .eq("work_order_no", dto.getWorkOrderNo().trim()).last("LIMIT 1"));
        if (workOrder == null) {
            throw new RuntimeException("工单号无效");
        }
        if (!WmsConstants.WORK_ORDER_STATUS_RELEASED.equals(workOrder.getWorkOrderStatus())
                && !WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS.equals(workOrder.getWorkOrderStatus())
                && !WmsConstants.WORK_ORDER_STATUS_FINISHED.equals(workOrder.getWorkOrderStatus())) {
            throw new RuntimeException("工单状态不允许领料");
        }
        if (dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("领料明细不能为空");
        }
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo(nextNo(WmsConstants.OUTBOUND_NO_PREFIX_PICK))
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_PICK)
                .setWorkOrderNo(workOrder.getWorkOrderNo())
                .setRequirementSource(oConvertUtils.isEmpty(dto.getRequirementSource())
                        ? WmsConstants.REQUIREMENT_SOURCE_MANUAL : dto.getRequirementSource().trim())
                .setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE)
                .setSyncStatus(WmsConstants.SYNC_STATUS_NOT_REQUIRED)
                .setRetryCount(0)
                .setSyncFailCount(0)
                .setOperator(WmsUtils.currentUsername())
                .setSubmitTime(new Date())
                .setRemark(dto.getRemark());
        persist(order, dto.getDetails());
        log.info("领料出库单创建：单号={}，工单={}，明细={}",
                order.getOutboundNo(), order.getWorkOrderNo(), dto.getDetails().size());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WmsOutboundOrder createSample(WmsOutboundSampleCreateDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getMaterialCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        if (dto.getQty() == null || dto.getQty().signum() <= 0) {
            throw new RuntimeException("样品数量必须大于 0");
        }
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", dto.getMaterialCode().trim()).last("LIMIT 1"));
        if (material == null) {
            throw new RuntimeException("物料编码不存在");
        }
        WmsOutboundDetailDTO detail = new WmsOutboundDetailDTO();
        detail.setMaterialCode(material.getMaterialCode());
        detail.setBatchNo(oConvertUtils.isEmpty(dto.getBatchNo()) ? null : dto.getBatchNo().trim());
        detail.setRequiredQty(dto.getQty());
        detail.setUnit(material.getUnit());

        // 样品出库刻意不绑工单/销售订单：这两条回写链路（生产领料/销售出库）都不适用
        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo(nextNo(WmsConstants.OUTBOUND_NO_PREFIX_SAMPLE))
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SAMPLE)
                .setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE)
                .setSyncStatus(WmsConstants.SYNC_STATUS_NOT_REQUIRED)
                .setRetryCount(0)
                .setSyncFailCount(0)
                .setOperator(WmsUtils.currentUsername())
                .setSubmitTime(new Date())
                .setRemark(dto.getRemark());
        persist(order, List.of(detail));
        log.info("样品出库单创建：单号={}，物料={}，数量={}",
                order.getOutboundNo(), material.getMaterialCode(), dto.getQty());
        return order;
    }

    @Override
    public WmsOutboundOrder createSales(WmsOutboundSalesCreateDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getSalesOrderNo())) {
            throw new RuntimeException("销售订单号不能为空");
        }
        String salesOrderNo = dto.getSalesOrderNo().trim();
        // CK-002 方向反转：明细与客户一律取金蝶快照，WMS 不再造单
        List<WmsSalesOrderDetail> snapshotLines = salesOrderService.requireApprovedDetails(salesOrderNo);
        WmsSalesOrder snapshot = salesOrderService.getById(snapshotLines.get(0).getSalesOrderId());
        if (snapshot == null) {
            throw new RuntimeException("销售订单快照不存在：" + salesOrderNo);
        }
        if (dto.getDetails() == null || dto.getDetails().isEmpty()) {
            throw new RuntimeException("出库明细不能为空");
        }
        Map<String, WmsSalesOrderDetail> lineByMaterial = new LinkedHashMap<>();
        for (WmsSalesOrderDetail line : snapshotLines) {
            lineByMaterial.put(line.getMaterialCode(), line);
        }
        for (WmsOutboundDetailDTO item : dto.getDetails()) {
            WmsSalesOrderDetail line = lineByMaterial.get(item.getMaterialCode());
            if (line == null) {
                throw new RuntimeException("物料[" + item.getMaterialCode() + "]不在销售订单 " + salesOrderNo + " 中");
            }
            if (oConvertUtils.isEmpty(line.getMaterialId())) {
                throw new RuntimeException("物料[" + line.getMaterialCode()
                        + "]未在 WMS 建档（请先在物料管理建档或同步物料）");
            }
            BigDecimal remaining = nvl(line.getOrderQty()).subtract(nvl(line.getShippedQty()));
            if (nvl(item.getRequiredQty()).compareTo(remaining) > 0) {
                throw new RuntimeException("物料[" + line.getMaterialCode() + "]申请数量 "
                        + strip(nvl(item.getRequiredQty())) + " 超过订单剩余可出数量 " + strip(remaining));
            }
            item.setUnit(oConvertUtils.isEmpty(item.getUnit()) ? line.getUnit() : item.getUnit());
            // 快照里存的是金蝶物料编码；落出库明细时转回 WMS 物料编码（FK 校验用 WMS 码）
            WmsMaterial material = materialMapper.selectById(line.getMaterialId());
            if (material == null) {
                throw new RuntimeException("物料[" + line.getMaterialCode() + "]未在 WMS 建档");
            }
            item.setMaterialCode(material.getMaterialCode());
        }

        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo(nextNo(WmsConstants.OUTBOUND_NO_PREFIX_SALES))
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SALES)
                .setSalesOrderNo(salesOrderNo)
                .setCustomerCode(snapshot.getCustomerCode())
                .setCustomerName(snapshot.getCustomerName())
                .setDeliveryDate(dto.getDeliveryDate() != null ? dto.getDeliveryDate()
                        : (snapshot.getDeliveryDate() != null ? snapshot.getDeliveryDate() : new Date()))
                .setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE)
                .setSyncStatus(WmsConstants.SYNC_STATUS_NOT_REQUIRED)
                .setRetryCount(0)
                .setSyncFailCount(0)
                .setOperator(WmsUtils.currentUsername())
                .setSubmitTime(new Date())
                .setRemark(dto.getRemark());
        persist(order, dto.getDetails());
        log.info("销售出库单创建（基于 ERP 销售订单）：单号={}，销售订单={}，明细={}",
                order.getOutboundNo(), salesOrderNo, dto.getDetails().size());
        return order;
    }

    @Override
    public WmsSalesOrderVO previewSalesOrder(String salesOrderNo) {
        return salesOrderService.preview(salesOrderNo);
    }

    @Override
    public int syncSalesOrder(WmsSalesOrderSyncDTO dto) {
        if (dto == null) {
            throw new RuntimeException("拉取参数不能为空");
        }
        if (oConvertUtils.isNotEmpty(dto.getNo())) {
            salesOrderService.syncByBillNo(dto.getNo().trim());
            return 1;
        }
        if (dto.getBeginDate() == null || dto.getEndDate() == null) {
            throw new RuntimeException("请提供销售订单号，或起始/结束日期");
        }
        return salesOrderService.syncByWindow(dto.getBeginDate(), dto.getEndDate());
    }

    @Override
    public com.baomidou.mybatisplus.core.metadata.IPage<WmsOutboundOrder> listPage(
            Integer pageNo, Integer pageSize, String outboundType, String status, String keyword) {
        LambdaQueryWrapper<WmsOutboundOrder> query = Wrappers.<WmsOutboundOrder>lambdaQuery()
                .eq(oConvertUtils.isNotEmpty(outboundType), WmsOutboundOrder::getOutboundType, outboundType)
                .eq(oConvertUtils.isNotEmpty(status), WmsOutboundOrder::getStatus, status)
                .and(oConvertUtils.isNotEmpty(keyword), w -> w
                        .like(WmsOutboundOrder::getOutboundNo, keyword)
                        .or().like(WmsOutboundOrder::getWorkOrderNo, keyword)
                        .or().like(WmsOutboundOrder::getSalesOrderNo, keyword)
                        .or().like(WmsOutboundOrder::getCustomerName, keyword))
                .orderByDesc(WmsOutboundOrder::getCreateTime);
        return page(new Page<>(pageNo == null ? 1 : pageNo, pageSize == null ? 10 : pageSize), query);
    }

    /** 主表 + 明细一次性落库。 */
    private void persist(WmsOutboundOrder order, List<WmsOutboundDetailDTO> details) {
        if (!save(order)) {
            throw new RuntimeException("出库单保存失败");
        }
        int lineNo = 1;
        for (WmsOutboundDetailDTO item : details) {
            WmsMaterial material = resolveMaterial(item);
            WmsOutboundDetail detail = new WmsOutboundDetail()
                    .setOutboundId(order.getId())
                    .setLineNo(lineNo++)
                    .setMaterialId(material.getId())
                    .setMaterialCode(material.getMaterialCode())
                    .setMaterialName(material.getMaterialName())
                    .setBatchNo(item.getBatchNo())
                    .setRequiredQty(item.getRequiredQty())
                    .setAllocatedQty(BigDecimal.ZERO)
                    .setActualQty(BigDecimal.ZERO)
                    .setUnit(oConvertUtils.isEmpty(item.getUnit()) ? material.getUnit() : item.getUnit())
                    .setSourceEntryId(item.getSourceEntryId());
            detailMapper.insert(detail);
        }
    }

    /** 明细物料校验：编码必填、存在、数量 > 0 且精度 ≤ 3。 */
    private WmsMaterial resolveMaterial(WmsOutboundDetailDTO item) {
        if (item == null || oConvertUtils.isEmpty(item.getMaterialCode())) {
            throw new RuntimeException("物料编码不能为空");
        }
        BigDecimal qty = item.getRequiredQty();
        if (qty == null || qty.signum() <= 0) {
            throw new RuntimeException("数量必须大于 0");
        }
        if (qty.stripTrailingZeros().scale() > 3) {
            throw new RuntimeException("数量精度不能超过 3 位小数");
        }
        WmsMaterial material = materialMapper.selectOne(Wrappers.<WmsMaterial>query()
                .eq("material_code", item.getMaterialCode().trim()).last("LIMIT 1"));
        if (material == null) {
            throw new RuntimeException("物料编码不存在：" + item.getMaterialCode().trim());
        }
        return material;
    }

    /** 生成业务单号：前缀 + 雪花 ID（多实例部署不撞号）。 */
    String nextNo(String prefix) {
        return prefix + IdWorker.getIdStr();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String strip(BigDecimal value) {
        return nvl(value).stripTrailingZeros().toPlainString();
    }
}
