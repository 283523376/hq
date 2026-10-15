package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.service.IWmsDeviceAlertService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description: 设备离线告警投递
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Slf4j
@Service
public class WmsDeviceAlertServiceImpl implements IWmsDeviceAlertService {

    private final ISysBaseAPI sysBaseAPI;

    /** 只为打点 / 清空 {@code last_alert_time}。直接注入 Mapper 而非设备服务，避免与之循环依赖。 */
    private final WmsDeviceMapper wmsDeviceMapper;

    /**
     * 告警收件人（登录账号）。
     *
     * <p><b>不可为 null</b>：平台 {@code SysBaseApiImpl.sendSysAnnouncement} 会对 toUser 执行
     * {@code split(",")}，null 会抛 NPE 并被 {@link #send} 的 try/catch 吞掉，
     * 结果只剩一行「投递失败」日志而管理员什么都收不到。
     */
    @Value("${" + WmsConstants.DEVICE_ALERT_TO_USER_KEY + ":" + WmsConstants.ALERT_TO_USER + "}")
    private String alertToUser = WmsConstants.ALERT_TO_USER;

    public WmsDeviceAlertServiceImpl(ISysBaseAPI sysBaseAPI, WmsDeviceMapper wmsDeviceMapper) {
        this.sysBaseAPI = sysBaseAPI;
        this.wmsDeviceMapper = wmsDeviceMapper;
    }

    @Override
    public List<String> alertOffline(List<WmsDevice> devices, boolean respectThrottle) {
        if (devices == null || devices.isEmpty()) {
            return Collections.emptyList();
        }
        Date now = new Date();
        List<WmsDevice> allowed = new ArrayList<>();
        for (WmsDevice device : devices) {
            if (device == null) {
                continue;
            }
            if (respectThrottle && !throttleExpired(device, now)) {
                log.debug("设备[{}]在告警节流窗口内，跳过投递（上次告警：{}）",
                        device.getDeviceCode(), device.getLastAlertTime());
                continue;
            }
            allowed.add(device);
        }
        if (allowed.isEmpty()) {
            log.info("本批 {} 台离线设备全部处于告警节流窗口内，未投递", devices.size());
            return Collections.emptyList();
        }
        List<String> codes = allowed.stream().map(WmsDevice::getDeviceCode).collect(Collectors.toList());
        send(buildContent(allowed));
        for (WmsDevice device : allowed) {
            stampAlertTime(device.getId(), now);
        }
        log.warn("设备离线告警已投递，共 {} 台：{}", allowed.size(), codes);
        return codes;
    }

    @Override
    public void clearThrottle(String deviceId) {
        if (oConvertUtils.isEmpty(deviceId)) {
            return;
        }
        // 必须用 UpdateWrapper：updateById 默认忽略 null 字段，置 NULL 会被静默跳过，
        // 于是 last_alert_time 永远清不掉，节流窗口会永久卡住。
        UpdateWrapper<WmsDevice> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", deviceId)
                .set("last_alert_time", null)
                .set("update_time", new Date())
                .set("update_by", WmsUtils.currentUsername());
        try {
            wmsDeviceMapper.update(null, wrapper);
        } catch (Exception e) {
            log.error("清空设备[{}]告警节流失败：{}", deviceId, e.getMessage(), e);
        }
    }

    /** 节流窗口是否已过。窗口为 {@code DEVICE_ALERT_THROTTLE_MINUTES} 分钟；从未告警过视为已过。 */
    private boolean throttleExpired(WmsDevice device, Date now) {
        if (device.getLastAlertTime() == null) {
            return true;
        }
        long silentMs = now.getTime() - device.getLastAlertTime().getTime();
        return silentMs >= WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES * 60_000L;
    }

    /**
     * 投递站内消息。
     *
     * <p>用 try/catch 包住 —— 告警设施自己出问题，不能掩盖「设备离线」这件事，
     * 更不能把异常抛回给绑定 / 上报 / 扫描的调用方。
     */
    private void send(String content) {
        try {
            MessageDTO message = new MessageDTO(WmsConstants.ALERT_FROM_USER, alertToUser,
                    WmsConstants.DEVICE_ALERT_TITLE, content);
            sysBaseAPI.sendSysAnnouncement(message);
        } catch (Exception e) {
            log.error("设备离线告警投递失败：{}", e.getMessage(), e);
        }
    }

    /** 聚合内容：一轮扫描可能几十台设备转离线，逐条投递会刷屏。 */
    private String buildContent(List<WmsDevice> devices) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder content = new StringBuilder("以下设备已离线，请及时处理：");
        for (WmsDevice device : devices) {
            content.append("\n设备编码：").append(device.getDeviceCode())
                    .append("；名称：").append(device.getDeviceName())
                    .append("；类型：").append(device.getDeviceType())
                    .append("；最近心跳：")
                    .append(device.getLastHeartbeat() == null ? "从未上报" : format.format(device.getLastHeartbeat()));
        }
        return content.toString();
    }

    /** 打点告警时间。失败只记日志 —— 节流打点丢掉不该影响告警本身的判定结果。 */
    private void stampAlertTime(String deviceId, Date now) {
        if (oConvertUtils.isEmpty(deviceId)) {
            return;
        }
        try {
            WmsDevice update = new WmsDevice();
            update.setId(deviceId);
            update.setLastAlertTime(now);
            WmsUtils.fillUpdate(update);
            wmsDeviceMapper.updateById(update);
        } catch (Exception e) {
            log.error("设备[{}]告警时间打点失败：{}", deviceId, e.getMessage(), e);
        }
    }
}