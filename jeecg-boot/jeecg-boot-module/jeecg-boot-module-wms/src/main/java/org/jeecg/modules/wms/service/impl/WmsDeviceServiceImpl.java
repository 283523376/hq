package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.service.IWmsDeviceService;
import org.jeecg.modules.wms.service.IWmsWarehouseService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsDeviceServiceImpl extends ServiceImpl<WmsDeviceMapper, WmsDevice>
        implements IWmsDeviceService {

    @Autowired
    private IWmsWarehouseService wmsWarehouseService;

    @Autowired
    private IWmsDeviceAlertService wmsDeviceAlertService;

    @Override
    public boolean checkCodeExists(String code, String id) {
        if (oConvertUtils.isEmpty(code)) {
            return false;
        }
        QueryWrapper<WmsDevice> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("device_code", code);
        if (oConvertUtils.isNotEmpty(id)) {
            queryWrapper.ne("id", id);
        }
        return this.count(queryWrapper) > 0;
    }

    @Override
    public void addDevice(WmsDevice device) {
        if (checkCodeExists(device.getDeviceCode(), null)) {
            throw new RuntimeException("设备编码已存在，请重新输入");
        }
        if (oConvertUtils.isEmpty(device.getStatus())) {
            // 没收到任何心跳前不应被当成在线设备投用
            device.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
        }
        stampHeartbeatIfOnline(device);
        WmsUtils.fillCreate(device);
        this.save(device);
    }

    @Override
    public void editDevice(WmsDevice device) {
        if (checkCodeExists(device.getDeviceCode(), device.getId())) {
            throw new RuntimeException("设备编码已存在，请重新输入");
        }
        stampHeartbeatIfOnline(device);
        WmsUtils.fillUpdate(device);
        this.updateById(device);
    }

    @Override
    public void removeDevice(String id) {
        this.removeById(id);
    }

    @Override
    public WmsDeviceBindResultVO bind(WmsDeviceBindDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getWarehouseId())) {
            throw new RuntimeException("库区不存在或已停用");
        }
        if (dto.getDeviceIds() == null || dto.getDeviceIds().isEmpty()) {
            throw new RuntimeException("请至少选择一台设备");
        }
        WmsWarehouse warehouse = wmsWarehouseService.requireEnabledWarehouse(dto.getWarehouseId());
        List<WmsDevice> devices = this.listByIds(dto.getDeviceIds());
        if (devices.isEmpty()) {
            throw new RuntimeException("所选设备不存在");
        }
        List<String> moved = new ArrayList<>();
        List<WmsDevice> offline = new ArrayList<>();
        for (WmsDevice device : devices) {
            if (oConvertUtils.isNotEmpty(device.getWarehouseId())
                    && !device.getWarehouseId().equals(dto.getWarehouseId())) {
                moved.add(device.getDeviceCode());
                log.warn("设备[{}]由库区[{}]搬迁到库区[{}]",
                        device.getDeviceCode(), device.getWarehouseId(), dto.getWarehouseId());
            }
            WmsDevice update = new WmsDevice();
            update.setId(device.getId());
            update.setWarehouseId(dto.getWarehouseId());
            WmsUtils.fillUpdate(update);
            this.updateById(update);
            if (WmsConstants.DEVICE_STATUS_OFFLINE.equals(device.getStatus())) {
                offline.add(device);
            }
        }
        List<String> offlineCodes = offline.stream()
                .map(WmsDevice::getDeviceCode).collect(Collectors.toList());
        String warning = null;
        if (!offlineCodes.isEmpty()) {
            // 绑定是人的即时操作 → 不节流，要即时反馈
            List<String> alerted = wmsDeviceAlertService.alertOffline(offline, false);
            if (!alerted.isEmpty()) {
                warning = "已绑定到库区[" + warehouse.getWarehouseName() + "]，但以下 "
                        + alerted.size() + " 台设备当前离线，已发出告警：" + String.join("、", alerted);
            }
        }
        log.info("库区[{}]绑定设备 {} 台，其中离线 {} 台，搬迁 {} 台",
                warehouse.getWarehouseCode(), devices.size(), offlineCodes.size(), moved.size());
        return new WmsDeviceBindResultVO(devices.size(), moved, offlineCodes, warning);
    }

    @Override
    public void unbind(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new RuntimeException("请至少选择一台设备");
        }
        // 必须用 UpdateWrapper：updateById 默认忽略 null 字段，
        // 用它「解绑」会返回成功而 warehouse_id 纹丝不动 —— 典型的「看起来已解绑」的静默故障。
        UpdateWrapper<WmsDevice> wrapper = new UpdateWrapper<>();
        wrapper.in("id", ids)
                .set("warehouse_id", null)
                .set("update_time", new Date())
                .set("update_by", WmsUtils.currentUsername());
        this.update(null, wrapper);
    }

    @Override
    public void reportStatus(WmsDeviceStatusDTO dto) {
        if (dto == null || oConvertUtils.isEmpty(dto.getDeviceCode())) {
            throw new RuntimeException("设备编码不能为空");
        }
        if (oConvertUtils.isEmpty(dto.getStatus())) {
            throw new RuntimeException("设备状态不能为空");
        }
        WmsDevice device = this.getOne(new QueryWrapper<WmsDevice>()
                .eq("device_code", dto.getDeviceCode()).last("limit 1"), false);
        if (device == null) {
            throw new RuntimeException("设备不存在：" + dto.getDeviceCode());
        }
        Date now = new Date();
        WmsDevice update = new WmsDevice();
        update.setId(device.getId());
        update.setStatus(dto.getStatus());
        update.setLastHeartbeat(now);
        WmsUtils.fillUpdate(update);
        this.updateById(update);

        boolean online = WmsConstants.DEVICE_STATUS_ONLINE.equals(dto.getStatus());
        if (online) {
            // 恢复在线 → 清空节流，使后续再次离线能立即告警
            wmsDeviceAlertService.clearThrottle(device.getId());
        } else {
            device.setStatus(dto.getStatus());
            device.setLastHeartbeat(now);
            wmsDeviceAlertService.alertOffline(Collections.singletonList(device), true);
        }
    }

    @Override
    public List<WmsDevice> scanOfflineDevices() {
        Date threshold = new Date(System.currentTimeMillis()
                - WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS * 1000L);
        List<WmsDevice> candidates = this.list(new QueryWrapper<WmsDevice>()
                // 只捕捉跃迁：已经是离线的设备不再重复处理
                .ne("status", WmsConstants.DEVICE_STATUS_OFFLINE)
                // 关键：从未上报过心跳的设备不参与。它们从未上线，判离线是误报，
                // 且会让新建设备集体变离线、淹没真实告警。
                .isNotNull("last_heartbeat")
                .lt("last_heartbeat", threshold)
                .orderByAsc("last_heartbeat")
                .last("limit " + WmsConstants.DEVICE_SCAN_BATCH));
        List<WmsDevice> turned = new ArrayList<>();
        for (WmsDevice device : candidates) {
            try {
                WmsDevice update = new WmsDevice();
                update.setId(device.getId());
                update.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
                WmsUtils.fillUpdate(update);
                this.updateById(update);
                device.setStatus(WmsConstants.DEVICE_STATUS_OFFLINE);
                log.warn("设备[{}]心跳静默超过 {} 秒，已置为离线（最近心跳：{}）",
                        device.getDeviceCode(), WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS,
                        device.getLastHeartbeat());
                turned.add(device);
            } catch (Exception e) {
                // 单条失败不中断整轮
                log.error("设备[{}]转离线失败：{}", device.getDeviceCode(), e.getMessage(), e);
            }
        }
        return turned;
    }

    /** 显式把状态置为「在线」时必须同时打心跳，否则扫描任务下一轮会把它判成离线。 */
    private void stampHeartbeatIfOnline(WmsDevice device) {
        if (WmsConstants.DEVICE_STATUS_ONLINE.equals(device.getStatus())) {
            device.setLastHeartbeat(new Date());
        }
    }
}