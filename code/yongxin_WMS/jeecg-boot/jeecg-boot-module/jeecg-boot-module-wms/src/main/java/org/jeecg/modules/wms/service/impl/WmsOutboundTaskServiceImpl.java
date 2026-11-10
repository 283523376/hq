package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsOutboundTaskService;
import org.jeecg.modules.wms.sync.IWmsRmsGateway;
import org.jeecg.modules.wms.sync.IWmsWcsGateway;
import org.jeecg.modules.wms.sync.WmsRmsOutboundTask;
import org.jeecg.modules.wms.sync.WmsRmsErrorCode;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 出库设备任务下发：设备故障检查 → 半成品逐库位走 WCS、成品按容器聚合走 RMS。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsOutboundTaskServiceImpl extends ServiceImpl<WmsOutboundOrderMapper, WmsOutboundOrder>
        implements IWmsOutboundTaskService {

    @Autowired
    private WmsOutboundAllocationMapper allocationMapper;

    @Autowired
    private WmsLocationMapper locationMapper;

    @Autowired
    private WmsWarehouseMapper warehouseMapper;

    @Autowired
    private WmsDeviceMapper deviceMapper;

    @Autowired
    private IWmsWcsGateway wcsGateway;

    @Autowired
    private IWmsRmsGateway rmsGateway;

    @Autowired
    private ISysBaseAPI sysBaseAPI;

    @Value("${wms.sync.rms.outbound-stations:}")
    private String outboundStations = "";

    @Value("${wms.sync.rms.outbound-priority:1}")
    private int outboundPriority = 1;

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    @Override
    public void dispatch(String id) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            throw new RuntimeException("出库单不存在");
        }
        boolean allowed = WmsConstants.OUTBOUND_STATUS_ALLOCATED.equals(order.getStatus())
                || WmsConstants.OUTBOUND_STATUS_EXCEPTION.equals(order.getStatus());
        if (!allowed) {
            throw new RuntimeException("当前状态不允许下发");
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id).orderByAsc("id"));
        List<WmsOutboundAllocation> pending = new ArrayList<>();
        if (allocations != null) {
            for (WmsOutboundAllocation allocation : allocations) {
                if (WmsConstants.TASK_STATUS_NOT_DISPATCHED.equals(allocation.getTaskStatus())
                        || WmsConstants.TASK_STATUS_FAILED.equals(allocation.getTaskStatus())) {
                    pending.add(allocation);
                }
            }
        }
        if (pending.isEmpty()) {
            throw new RuntimeException("无可下发的分配任务");
        }
        boolean pick = WmsConstants.OUTBOUND_TYPE_PICK.equals(order.getOutboundType());

        String warehouseId;
        if (pick) {
            for (WmsOutboundAllocation allocation : pending) {
                WmsLocation location = allocation.getLocationId() == null
                        ? null : locationMapper.selectById(allocation.getLocationId());
                if (location == null) {
                    markException(order, "立库库位不存在：" + allocation.getLocationId(),
                            WmsConstants.OUTBOUND_LOCATION_ALERT_TITLE);
                    return;
                }
            }
            warehouseId = locationMapper.selectById(pending.get(0).getLocationId()).getWarehouseId();
        } else {
            WmsWarehouse warehouse = warehouseMapper.selectOne(Wrappers.<WmsWarehouse>query()
                    .eq("warehouse_type", WmsConstants.WAREHOUSE_TYPE_AGILE).last("LIMIT 1"));
            warehouseId = warehouse == null ? null : warehouse.getId();
        }

        String faultDevice = findFaultDevice(warehouseId,
                pick ? WmsConstants.DEVICE_TYPE_STACKER : WmsConstants.DEVICE_TYPE_SCR);
        if (faultDevice != null) {
            markException(order, "设备故障（" + faultDevice + "），已转人工处理",
                    WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE);
            return;
        }

        if (pick) {
            boolean failed = false;
            String lastError = null;
            for (WmsOutboundAllocation allocation : pending) {
                WmsTaskDispatchResult result = wcsGateway.dispatchOutbound(order, allocation);
                if (result.isSuccess() || isIdempotent(result)) {
                    // 「未配置地址按模拟受理」不是失败原因，不能写进 task_error，否则页面显示伪错误
                    markAllocation(allocation.getId(), WmsConstants.TASK_STATUS_ACCEPTED, null);
                } else {
                    failed = true;
                    lastError = result.getErrorMessage();
                    markAllocation(allocation.getId(), WmsConstants.TASK_STATUS_FAILED,
                            result.getErrorMessage());
                }
            }
            if (failed) {
                markException(order, "下发失败：" + lastError, WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE);
                return;
            }
        } else {
            Map<String, List<WmsOutboundAllocation>> byContainer = new LinkedHashMap<>();
            for (WmsOutboundAllocation allocation : pending) {
                byContainer.computeIfAbsent(allocation.getContainerCode(), k -> new ArrayList<>()).add(allocation);
            }
            List<WmsRmsOutboundTask> tasks = new ArrayList<>();
            for (Map.Entry<String, List<WmsOutboundAllocation>> entry : byContainer.entrySet()) {
                String taskNo = entry.getValue().get(0).getTaskNo();
                for (WmsOutboundAllocation allocation : entry.getValue()) {
                    if (!taskNo.equals(allocation.getTaskNo())) {
                        unifyTaskNo(allocation.getId(), taskNo);
                    }
                }
                WmsRmsOutboundTask task = new WmsRmsOutboundTask();
                task.setTaskNo(taskNo);
                task.setContainerCode(entry.getKey());
                task.setPriority(outboundPriority);
                task.setStationCodes(parseStations());
                tasks.add(task);
            }
            WmsTaskDispatchResult result = rmsGateway.dispatchOutbound(tasks);
            if (result.isSuccess() || isIdempotent(result)) {
                for (WmsOutboundAllocation allocation : pending) {
                    markAllocation(allocation.getId(), WmsConstants.TASK_STATUS_ACCEPTED, null);
                }
            } else {
                for (WmsOutboundAllocation allocation : pending) {
                    markAllocation(allocation.getId(), WmsConstants.TASK_STATUS_FAILED,
                            result.getErrorMessage());
                }
                markException(order, "下发失败：" + result.getErrorMessage(),
                        WmsConstants.OUTBOUND_DEVICE_ALERT_TITLE);
                return;
            }
        }

        updateOrderStatus(id, WmsConstants.OUTBOUND_STATUS_EXECUTING, null);
        log.info("出库单[{}]任务已受理：类型={}，分配行={}",
                order.getOutboundNo(), order.getOutboundType(), pending.size());
    }

    @Override
    public void cancel(String id) {
        WmsOutboundOrder order = getById(id);
        if (order == null) {
            return;
        }
        List<WmsOutboundAllocation> allocations = allocationMapper.selectList(
                Wrappers.<WmsOutboundAllocation>query().eq("outbound_id", id));
        if (allocations == null) {
            return;
        }
        for (WmsOutboundAllocation allocation : allocations) {
            if (oConvertUtils.isEmpty(allocation.getTaskNo())
                    || WmsConstants.TASK_STATUS_FINISHED.equals(allocation.getTaskStatus())
                    || WmsConstants.TASK_STATUS_CLOSED.equals(allocation.getTaskStatus())) {
                continue;
            }
            try {
                if (WmsConstants.OUTBOUND_TYPE_PICK.equals(order.getOutboundType())) {
                    wcsGateway.cancelOutbound(order, allocation);
                } else {
                    rmsGateway.cancelOutbound(allocation.getTaskNo(), allocation.getContainerCode());
                }
                markAllocation(allocation.getId(), WmsConstants.TASK_STATUS_CLOSED, null);
            } catch (Exception e) {
                log.warn("出库任务[{}]取消失败（不阻塞关单）：{}", allocation.getTaskNo(), e.getMessage());
            }
        }
    }

    private boolean isIdempotent(WmsTaskDispatchResult result) {
        return result.getErrorCode() != null
                && WmsRmsErrorCode.isOutboundIdempotentSuccess(result.getErrorCode());
    }

    private String findFaultDevice(String warehouseId, String deviceType) {
        if (warehouseId == null) {
            return null;
        }
        List<WmsDevice> faults = deviceMapper.selectList(Wrappers.<WmsDevice>query()
                .eq("warehouse_id", warehouseId)
                .eq("device_type", deviceType)
                .eq("status", WmsConstants.DEVICE_STATUS_FAULT));
        return faults == null || faults.isEmpty() ? null : faults.get(0).getDeviceCode();
    }

    private List<String> parseStations() {
        if (oConvertUtils.isEmpty(outboundStations)) {
            return List.of();
        }
        List<String> stations = new ArrayList<>();
        for (String item : outboundStations.split(",")) {
            if (oConvertUtils.isNotEmpty(item.trim())) {
                stations.add(item.trim());
            }
        }
        return stations;
    }

    private void markAllocation(String allocationId, String status, String error) {
        allocationMapper.update(null, new UpdateWrapper<WmsOutboundAllocation>()
                .set("task_status", status)
                .set("task_error", truncate(error))
                .set("update_time", new Date())
                .eq("id", allocationId)
                .in("task_status", WmsConstants.TASK_STATUS_NOT_DISPATCHED,
                        WmsConstants.TASK_STATUS_FAILED));
    }

    private void unifyTaskNo(String allocationId, String taskNo) {
        allocationMapper.update(null, new UpdateWrapper<WmsOutboundAllocation>()
                .set("task_no", taskNo)
                .set("update_time", new Date())
                .eq("id", allocationId));
    }

    private void markException(WmsOutboundOrder order, String message, String title) {
        updateOrderStatus(order.getId(), WmsConstants.OUTBOUND_STATUS_EXCEPTION, truncate(message));
        alert(title, "出库单：" + order.getOutboundNo() + "；原因：" + message);
        log.warn("出库单[{}]转人工：{}", order.getOutboundNo(), message);
    }

    private void updateOrderStatus(String id, String status, String lastSyncError) {
        WmsOutboundOrder patch = new WmsOutboundOrder().setStatus(status).setLastSyncError(lastSyncError);
        patch.setUpdateTime(new Date());
        baseMapper.update(patch, new UpdateWrapper<WmsOutboundOrder>()
                .eq("id", id)
                .in("status", WmsConstants.OUTBOUND_STATUS_ALLOCATED,
                        WmsConstants.OUTBOUND_STATUS_EXCEPTION,
                        WmsConstants.OUTBOUND_STATUS_EXECUTING));
    }

    private void alert(String title, String content) {
        try {
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    alertToUser == null ? WmsConstants.ALERT_TO_USER : alertToUser, title, content));
        } catch (Exception e) {
            log.error("出库任务告警投递失败：{}", e.getMessage(), e);
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
