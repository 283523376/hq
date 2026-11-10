package org.jeecg.modules.wms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
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
     * 保存某分组的全部配置：校验 → upsert → 留痕，单事务。
     *
     * @throws org.jeecg.modules.wms.exception.WmsConfigConflictException 校验不通过（message 逐字为蓝图原文）
     */
    void saveGroup(WmsConfigSaveDTO dto);

    /**
     * 把某分组整体回滚到上一版本。
     *
     * @throws RuntimeException 无任何可回滚项（提示文案与「冲突」不同）
     * @throws org.jeecg.modules.wms.exception.WmsConfigConflictException 回滚目标值过不了当前校验
     */
    void rollback(WmsConfigRollbackDTO dto);

    /** 某配置键的版本流水（版本降序），让留痕可查证。 */
    List<WmsSystemConfigHistory> history(String key);

    /**
     * 只读整数配置。
     *
     * <p><b>读不到 / 值为空 / 非法 / ≤0 一律返回 fallback 并打 WARN，绝不抛异常</b> ——
     * 配置写坏不能让调用方（设备离线扫描任务）跟着失败：监控链路断了比阈值不准严重得多。
     */
    int getInt(String key, int fallback);

    /**
     * 只读字符串配置。读不到 / 值为空 / 键未知一律返回 fallback 并打 WARN，绝不抛异常。
     */
    String getString(String key, String fallback);

    /**
     * 读布尔配置（如「自动同步」{@code sync.auto}）。
     *
     * <p>值不是 true/false 或读库失败时打 warn 并返回兜底值——
     * 配置读不出来不该让定时任务或业务链路失败。
     */
    boolean getBool(String key, boolean fallback);
}
