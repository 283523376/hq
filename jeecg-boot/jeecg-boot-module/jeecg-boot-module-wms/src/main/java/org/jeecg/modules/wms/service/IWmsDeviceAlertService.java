package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsDevice;

import java.util.List;

/**
 * @Description: 设备离线告警投递
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsDeviceAlertService {

    /**
     * 聚合告警：对本批设备中通过节流判定的那些，发一条站内消息，并为其打点 {@code last_alert_time}。
     *
     * @param devices         待告警设备（本批已判定为离线）
     * @param respectThrottle true = 按 {@code DEVICE_ALERT_THROTTLE_MINUTES} 窗口节流；
     *                        false = 不节流。绑定路径传 false —— 那是人的即时操作，
     *                        要的就是即时反馈，不该被上一次的自动告警压掉
     * @return 实际告警的设备编码；被节流跳过的不在其中
     */
    List<String> alertOffline(List<WmsDevice> devices, boolean respectThrottle);

    /** 清空某设备的告警节流（恢复在线时调用），使后续再次离线能立即告警。 */
    void clearThrottle(String deviceId);
}