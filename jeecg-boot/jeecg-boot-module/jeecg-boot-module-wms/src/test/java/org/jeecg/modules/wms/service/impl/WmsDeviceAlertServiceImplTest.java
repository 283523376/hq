package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 设备告警服务单测（不启 Spring 上下文）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsDeviceAlertServiceImplTest {

    private static WmsDevice device(String id, String code, Date lastAlertTime) {
        WmsDevice d = new WmsDevice().setDeviceCode(code).setDeviceName(code + "-名称")
                .setDeviceType(WmsConstants.DEVICE_TYPE_STACKER)
                .setStatus(WmsConstants.DEVICE_STATUS_OFFLINE)
                .setLastHeartbeat(new Date())
                .setLastAlertTime(lastAlertTime);
        d.setId(id);
        return d;
    }

    private static WmsDeviceAlertServiceImpl newService(ISysBaseAPI sysBaseAPI, WmsDeviceMapper mapper) {
        WmsDeviceAlertServiceImpl service = new WmsDeviceAlertServiceImpl(sysBaseAPI, mapper);
        WmsTestSupport.inject(service, "alertToUser", WmsConstants.ALERT_TO_USER);
        // 节流窗口改由系统配置提供（BD-004）；未特别打桩时一律回落常量，保持既有用例语义不变
        IWmsSystemConfigService configService = mock(IWmsSystemConfigService.class);
        when(configService.getInt(any(String.class), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        WmsTestSupport.inject(service, "wmsSystemConfigService", configService);
        return service;
    }

    private static Date minutesAgo(int minutes) {
        return new Date(System.currentTimeMillis() - minutes * 60_000L);
    }

    // ------------------------------------------------------------ 绑定路径：不节流，人的即时操作要即时反馈

    @Test
    void alertOffline_shouldSendImmediatelyWhenThrottleNotRespected() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        // 1 分钟前刚告警过，但绑定是人工操作 → 仍应投递
        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(1))), false);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any(MessageDTO.class));
    }

    // ------------------------------------------------------------ 上报/扫描路径：30 分钟节流

    @Test
    void alertOffline_shouldSkipWithinThrottleWindow() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(5))), true);

        assertTrue(alerted.isEmpty());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
        verify(mapper, never()).updateById(any(WmsDevice.class));
    }

    @Test
    void alertOffline_shouldSendAfterThrottleWindow() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", minutesAgo(40))), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any(MessageDTO.class));
    }

    @Test
    void alertOffline_shouldSendWhenNeverAlertedBefore() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", null)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
    }

    /** 节流窗口边界应放行（不能写成严格大于）。 */
    @Test
    void alertOffline_shouldSendAtExactlyThrottleBoundary() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        // 恰好在窗口之外（多减 50ms 抵消执行耗时）
        Date justOutside = new Date(System.currentTimeMillis()
                - WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES * 60_000L - 50);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", justOutside)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted);
    }

    // ------------------------------------------------------------ 聚合：N 台设备 → 1 条消息

    @Test
    void alertOffline_shouldAggregateIntoOneMessage() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(Arrays.asList(
                device("D1", "DDJ01", null),
                device("D2", "SCR01", null),
                device("D3", "SCR02", null)), true);

        assertEquals(3, alerted.size());
        ArgumentCaptor<MessageDTO> captor = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI, times(1)).sendSysAnnouncement(captor.capture());
        String content = captor.getValue().getContent();
        assertTrue(content.contains("DDJ01"), "内容应含全部设备编码");
        assertTrue(content.contains("SCR01"));
        assertTrue(content.contains("SCR02"));
        assertEquals(WmsConstants.DEVICE_ALERT_TITLE, captor.getValue().getTitle());
        assertEquals(WmsConstants.ALERT_FROM_USER, captor.getValue().getFromUser());
        assertEquals(WmsConstants.ALERT_TO_USER, captor.getValue().getToUser(),
                "toUser 不可为 null：平台会 split(\",\")，null 会让告警静默丢失");
    }

    /** 混合场景：只有过期的那些被投递，且都打上新的告警时间。 */
    @Test
    void alertOffline_shouldOnlyAlertExpiredOnes() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(Arrays.asList(
                device("D1", "FRESH", minutesAgo(5)),
                device("D2", "STALE", minutesAgo(60))), true);

        assertEquals(Collections.singletonList("STALE"), alerted);
        verify(mapper, times(1)).updateById(any(WmsDevice.class));
    }

    // ------------------------------------------------------------ 投递失败不得反噬调用方

    /**
     * 告警设施自己的毛病不能掩盖「设备离线」这件事本身，
     * 更不能把异常抛回给绑定 / 上报 / 扫描的调用方。
     */
    @Test
    void alertOffline_shouldNotThrowWhenDeliveryFails() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        doThrow(new RuntimeException("公告服务不可用")).when(sysBaseAPI).sendSysAnnouncement(any(MessageDTO.class));
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mapper);

        List<String> alerted = service.alertOffline(
                Collections.singletonList(device("D1", "DDJ01", null)), true);

        assertEquals(Collections.singletonList("DDJ01"), alerted,
                "投递失败仍应返回「判定为需要告警」的清单，并继续打点");
    }

    @Test
    void alertOffline_shouldTolerateEmptyInput() {
        ISysBaseAPI sysBaseAPI = mock(ISysBaseAPI.class);
        WmsDeviceAlertServiceImpl service = newService(sysBaseAPI, mock(WmsDeviceMapper.class));

        assertTrue(service.alertOffline(Collections.emptyList(), true).isEmpty());
        assertTrue(service.alertOffline(null, true).isEmpty());
        verify(sysBaseAPI, never()).sendSysAnnouncement(any(MessageDTO.class));
    }

    // ------------------------------------------------------------ 恢复在线：清空节流

    /**
     * {@code updateById} 默认忽略 null 字段，故清空必须走 {@code UpdateWrapper}。
     * 若这里退回 updateById，{@code last_alert_time} 永远清不掉，节流窗口会永久卡住。
     */
    @Test
    void clearThrottle_shouldWriteNullViaUpdateWrapper() {
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        when(mapper.update(any(), any(Wrapper.class))).thenReturn(1);
        WmsDeviceAlertServiceImpl service = newService(mock(ISysBaseAPI.class), mapper);

        service.clearThrottle("D1");

        verify(mapper, times(1)).update(any(), any(Wrapper.class));
        verify(mapper, never()).updateById(any(WmsDevice.class));
    }

    @Test
    void clearThrottle_shouldIgnoreBlankId() {
        WmsDeviceMapper mapper = mock(WmsDeviceMapper.class);
        WmsDeviceAlertServiceImpl service = newService(mock(ISysBaseAPI.class), mapper);

        service.clearThrottle(null);
        service.clearThrottle("");

        verify(mapper, never()).update(any(), any(Wrapper.class));
    }
}