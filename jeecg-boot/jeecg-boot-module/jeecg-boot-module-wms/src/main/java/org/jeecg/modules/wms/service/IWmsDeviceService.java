package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsDeviceBindDTO;
import org.jeecg.modules.wms.dto.WmsDeviceStatusDTO;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.vo.WmsDeviceBindResultVO;

import java.util.List;

/**
 * @Description: 设备
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
public interface IWmsDeviceService extends IService<WmsDevice> {

    /** 设备编码唯一性校验。{@code id} 非空时排除自身。 */
    boolean checkCodeExists(String code, String id);

    void addDevice(WmsDevice device);

    void editDevice(WmsDevice device);

    void removeDevice(String id);

    /**
     * 绑定设备到库区。
     *
     * <p><b>绑定本身成功与告警是两件事</b>（蓝图异常分支「设备绑定失败 → 保存绑定状态并告警」）：
     * 离线设备照样绑定成功，只额外产生告警，不回滚、不阻断。
     */
    WmsDeviceBindResultVO bind(WmsDeviceBindDTO dto);

    /** 解绑：把 {@code warehouse_id} 置 NULL。 */
    void unbind(List<String> ids);

    /**
     * WCS/RMS 状态上报。在线会刷新心跳并清空告警节流；离线/故障按节流告警。
     *
     * <p><b>接入 WCS/RMS 前必读</b>：本接口当前走平台 Shiro 登录态（需 JWT），
     * 而 WCS/RMS 是**外部系统、拿不到用户 JWT**（代码审查发现）。
     * 真正接入属蓝图流程 `YX-WMS-SB-002`，届时需要改鉴权方式 ——
     * **但不可简单加 `@IgnoreAuth`**：那会开出一个任何匿名调用者都能改设备状态的接口。
     * 正确做法是设备级凭证（如界面参数里配置的 appKey/appSecret 或双向证书）+ 只允许
     * 上报其自身设备的状态。
     */
    void reportStatus(WmsDeviceStatusDTO dto);

    /**
     * 扫描心跳静默超时的设备并置为离线，返回本次发生跃迁的设备（已在库中置为离线）。
     *
     * <p><b>从未上报过心跳（{@code last_heartbeat IS NULL}）的设备不参与</b> ——
     * 它们从未上线，判离线是误报，且会让新建设备集体变离线、淹没真实告警。
     *
     * <p>本方法**只负责状态跃迁与日志**，告警由调用方（定时任务）聚合后投递，
     * 以便两者可分别测试。
     */
    List<WmsDevice> scanOfflineDevices();
}