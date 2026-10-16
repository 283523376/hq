package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;

import java.util.List;

/**
 * @Description: 系统配置
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
public interface IWmsSystemConfigService extends IService<WmsSystemConfig> {

    /** 页面首载的唯一入口：分组结构 + 全部组的值。 */
    List<WmsConfigGroupVO> definitions();

    /** 取某组的项（含当前值与版本信息），供局部刷新。 */
    List<WmsConfigItemVO> listByGroup(String groupCode);

    /** 某配置键当前的最大版本号；0 表示从未保存过。 */
    int currentVersionNo(String key);

    /**
     * 只读整数配置。
     *
     * <p><b>读不到 / 值为空 / 非法 / ≤0 一律返回 fallback 并打 WARN，绝不抛异常</b> ——
     * 配置写坏不能让调用方（设备离线扫描任务）跟着失败：监控链路断了比阈值不准严重得多。
     */
    int getInt(String key, int fallback);
}
