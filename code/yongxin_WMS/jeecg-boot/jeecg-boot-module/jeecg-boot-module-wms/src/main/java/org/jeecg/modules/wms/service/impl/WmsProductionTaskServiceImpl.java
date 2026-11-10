package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsProductionTaskService;
import org.jeecg.modules.wms.sync.IWmsRmsGateway;
import org.jeecg.modules.wms.sync.IWmsWcsGateway;
import org.jeecg.modules.wms.sync.WmsTaskDispatchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Description: 设备任务下发实现：设备故障检查 → 分流网关 → 受理/转人工。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Slf4j
@Service
public class WmsProductionTaskServiceImpl implements IWmsProductionTaskService {

    private final WmsProductionInboundMapper inboundMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsDeviceMapper deviceMapper;
    private final IWmsRmsGateway rmsGateway;
    private final IWmsWcsGateway wcsGateway;
    private final ISysBaseAPI sysBaseAPI;

    /** 异步下发线程池：HTTP 提交只负责落库，设备调用不阻塞用户请求。 */
    private final ExecutorService dispatchExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "wms-production-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${wms.sync.alert.to-user:" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsProductionTaskServiceImpl(WmsProductionInboundMapper inboundMapper,
                                        WmsWarehouseMapper warehouseMapper,
                                        WmsLocationMapper locationMapper,
                                        WmsDeviceMapper deviceMapper,
                                        IWmsRmsGateway rmsGateway,
                                        IWmsWcsGateway wcsGateway,
                                        ISysBaseAPI sysBaseAPI) {
        this.inboundMapper = inboundMapper;
        this.warehouseMapper = warehouseMapper;
        this.locationMapper = locationMapper;
        this.deviceMapper = deviceMapper;
        this.rmsGateway = rmsGateway;
        this.wcsGateway = wcsGateway;
        this.sysBaseAPI = sysBaseAPI;
    }

    @Override
    public void dispatchInboundAsync(String inboundId) {
        dispatchExecutor.execute(() -> {
            try {
                dispatchInbound(inboundId);
            } catch (Exception e) {
                log.error("生产入库任务[{}]异步下发异常：{}", inboundId, e.getMessage(), e);
            }
        });
    }

    @Override
    public void dispatchInbound(String inboundId) {
        WmsProductionInbound record = inboundMapper.selectById(inboundId);
        if (record == null) {
            throw new RuntimeException("生产入库记录不存在");
        }
        // 允许「待上架」与「异常待人工（设备故障修复后）」重新下发；
        // 已入库/已关闭等终态不得再下发，否则会覆盖关单结果。
        boolean statusAllowed = WmsConstants.PROD_STATUS_PENDING_PUTAWAY.equals(record.getStatus())
                || WmsConstants.PROD_STATUS_EXCEPTION.equals(record.getStatus());
        boolean taskAllowed = WmsConstants.TASK_STATUS_NOT_DISPATCHED.equals(record.getTaskStatus())
                || WmsConstants.TASK_STATUS_FAILED.equals(record.getTaskStatus());
        if (!statusAllowed || !taskAllowed) {
            throw new RuntimeException("当前状态不允许重新下发");
        }

        String faultDevice = findFaultDevice(record);
        if (faultDevice != null) {
            markManual(record, "设备故障（" + faultDevice + "），已转人工处理");
            return;
        }

        WmsTaskDispatchResult result;
        if (WmsConstants.PRODUCT_TYPE_SEMI.equals(record.getProductType())) {
            WmsLocation location = locationMapper.selectById(record.getLocationId());
            if (location == null) {
                markManual(record, "立库库位不存在，已转人工处理");
                return;
            }
            result = wcsGateway.dispatchInbound(record, location.getLocationCode());
        } else {
            result = rmsGateway.dispatchInbound(record.getWcsTaskNo(), record.getCarrierCode());
        }

        if (result.isSuccess()
                || (result.getErrorCode() != null
                && result.getErrorCode() == WmsConstants.RMS_ERROR_TASK_EXISTS)) {
            // 条件回写：下发在途期间记录可能已被关单/入库，不能无条件覆盖
            int affected = inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY)
                    .set("task_status", WmsConstants.TASK_STATUS_ACCEPTED)
                    .setSql("task_error = NULL")
                    .set("update_time", new Date())
                    .eq("id", record.getId())
                    .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY, WmsConstants.PROD_STATUS_EXCEPTION)
                    .in("task_status", WmsConstants.TASK_STATUS_NOT_DISPATCHED, WmsConstants.TASK_STATUS_FAILED));
            if (affected == 0) {
                log.info("生产入库任务[{}]下发返回受理，但记录已被关单/入库，忽略本次回写",
                        record.getWcsTaskNo());
                return;
            }
            log.info("生产入库任务已受理：单号={}，任务号={}，mocked={}",
                    record.getInboundNo(), record.getWcsTaskNo(), result.isMocked());
            return;
        }
        String message = "下发失败：" + (result.getErrorCode() == null ? "" : result.getErrorCode() + " ")
                + (result.getErrorMessage() == null ? "" : result.getErrorMessage());
        markManual(record, message);
    }

    @Override
    public WmsTaskDispatchResult forceInbound(String inboundId) {
        WmsProductionInbound record = inboundMapper.selectById(inboundId);
        if (record == null) {
            throw new RuntimeException("生产入库记录不存在");
        }
        // 强制入库是 RMS（成品敏捷仓）语义；半成品走 WCS，不适用
        if (!WmsConstants.PRODUCT_TYPE_FINISHED.equals(record.getProductType())) {
            throw new RuntimeException("仅成品（RMS）支持强制入库");
        }
        boolean allowed = WmsConstants.PROD_STATUS_PENDING_PUTAWAY.equals(record.getStatus())
                || WmsConstants.PROD_STATUS_EXECUTING.equals(record.getStatus())
                || WmsConstants.PROD_STATUS_EXCEPTION.equals(record.getStatus());
        if (!allowed) {
            throw new RuntimeException("当前状态不允许强制入库");
        }
        WmsTaskDispatchResult result = rmsGateway.forceInbound(record.getWcsTaskNo(), record.getCarrierCode());
        if (result.isSuccess()) {
            inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY)
                    .set("task_status", WmsConstants.TASK_STATUS_ACCEPTED)
                    .setSql("task_error = NULL")
                    .set("update_time", new Date())
                    .eq("id", record.getId())
                    .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY,
                            WmsConstants.PROD_STATUS_EXECUTING, WmsConstants.PROD_STATUS_EXCEPTION));
            log.info("生产入库强制入库已受理：单号={}，任务号={}",
                    record.getInboundNo(), record.getWcsTaskNo());
        }
        return result;
    }

    @Override
    public void cancelInbound(String inboundId) {
        WmsProductionInbound record = inboundMapper.selectById(inboundId);
        if (record == null) {
            return;
        }
        try {
            if (WmsConstants.PRODUCT_TYPE_SEMI.equals(record.getProductType())) {
                WmsLocation location = locationMapper.selectById(record.getLocationId());
                wcsGateway.cancelInbound(record, location == null ? null : location.getLocationCode());
            } else {
                rmsGateway.cancelInbound(record.getWcsTaskNo(), record.getCarrierCode());
            }
        } catch (Exception e) {
            // 取消失败要把原因落库，界面上能看到；但不阻塞关单
            String error = truncate("取消失败：" + e.getMessage());
            inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                    .set("task_error", error)
                    .set("update_time", new Date())
                    .eq("id", record.getId()));
            log.warn("生产入库任务[{}]取消失败（已落库，不阻塞关单）：{}",
                    record.getWcsTaskNo(), e.getMessage());
        }
    }

    /** 查目标库区绑定且类型匹配的故障设备；无绑定设备视为可用。 */
    private String findFaultDevice(WmsProductionInbound record) {
        String deviceType;
        String warehouseId;
        if (WmsConstants.PRODUCT_TYPE_SEMI.equals(record.getProductType())) {
            deviceType = WmsConstants.DEVICE_TYPE_STACKER;
            WmsLocation location = locationMapper.selectById(record.getLocationId());
            warehouseId = location == null ? null : location.getWarehouseId();
        } else {
            deviceType = WmsConstants.DEVICE_TYPE_SCR;
            WmsWarehouse warehouse = warehouseMapper.selectOne(Wrappers.<WmsWarehouse>query()
                    .eq("warehouse_type", WmsConstants.WAREHOUSE_TYPE_AGILE).last("LIMIT 1"));
            warehouseId = warehouse == null ? null : warehouse.getId();
        }
        if (warehouseId == null) {
            return null;
        }
        List<WmsDevice> faults = deviceMapper.selectList(Wrappers.<WmsDevice>query()
                .eq("warehouse_id", warehouseId)
                .eq("device_type", deviceType)
                .eq("status", WmsConstants.DEVICE_STATUS_FAULT));
        return faults == null || faults.isEmpty() ? null : faults.get(0).getDeviceCode();
    }

    private void markManual(WmsProductionInbound record, String message) {
        String error = truncate(message);
        // 条件回写：记录已关单/已入库时不得复活成异常待人工
        int affected = inboundMapper.update(null, new UpdateWrapper<WmsProductionInbound>()
                .set("status", WmsConstants.PROD_STATUS_EXCEPTION)
                .set("task_status", WmsConstants.TASK_STATUS_FAILED)
                .set("task_error", error)
                .set("update_time", new Date())
                .eq("id", record.getId())
                .in("status", WmsConstants.PROD_STATUS_PENDING_PUTAWAY, WmsConstants.PROD_STATUS_EXCEPTION)
                .in("task_status", WmsConstants.TASK_STATUS_NOT_DISPATCHED, WmsConstants.TASK_STATUS_FAILED));
        if (affected == 0) {
            log.info("生产入库[{}]下发失败，但记录已关单/入库，不再转人工",
                    record.getInboundNo());
            return;
        }
        alert(record, error);
        log.warn("生产入库转人工：单号={}，任务号={}，原因={}",
                record.getInboundNo(), record.getWcsTaskNo(), error);
    }

    private void alert(WmsProductionInbound record, String error) {
        try {
            String content = String.format("入库单号：%s；工单：%s；载具：%s；任务号：%s；原因：%s",
                    record.getInboundNo(), record.getWorkOrderNo(), record.getCarrierCode(),
                    record.getWcsTaskNo(), error);
            sysBaseAPI.sendSysAnnouncement(new MessageDTO(WmsConstants.ALERT_FROM_USER,
                    alertToUser == null ? WmsConstants.ALERT_TO_USER : alertToUser,
                    WmsConstants.PRODUCTION_DEVICE_ALERT_TITLE, content));
        } catch (Exception e) {
            log.error("生产入库告警投递失败：{}", e.getMessage(), e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= WmsConstants.SYNC_ERROR_MAX_LENGTH
                ? text : text.substring(0, WmsConstants.SYNC_ERROR_MAX_LENGTH);
    }

    @PreDestroy
    public void shutdown() {
        dispatchExecutor.shutdown();
    }
}
