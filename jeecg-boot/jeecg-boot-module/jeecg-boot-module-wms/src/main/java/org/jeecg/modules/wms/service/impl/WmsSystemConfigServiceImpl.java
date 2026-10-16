package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConfigGroup;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConfigValidator;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsConfigItemDTO;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.mapper.WmsSystemConfigHistoryMapper;
import org.jeecg.modules.wms.mapper.WmsSystemConfigMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.util.WmsUtils;
import org.jeecg.modules.wms.vo.WmsConfigConflict;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 系统配置
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
@Slf4j
@Service
public class WmsSystemConfigServiceImpl extends ServiceImpl<WmsSystemConfigMapper, WmsSystemConfig>
        implements IWmsSystemConfigService {

    @Autowired
    private WmsSystemConfigHistoryMapper wmsSystemConfigHistoryMapper;

    @Override
    public List<WmsConfigGroupVO> definitions() {
        Map<String, WmsSystemConfig> stored = loadStoredMap();
        List<WmsConfigGroupVO> groups = new ArrayList<>();
        for (WmsConfigGroup group : WmsConfigGroup.orderedAsList()) {
            WmsConfigGroupVO vo = new WmsConfigGroupVO();
            vo.setCode(group.getCode());
            vo.setName(group.getName());
            List<WmsConfigItemVO> items = new ArrayList<>();
            for (WmsConfigKey key : WmsConfigKey.ofGroup(group)) {
                items.add(toItemVO(key, stored.get(key.getKey())));
            }
            vo.setItems(items);
            groups.add(vo);
        }
        return groups;
    }

    @Override
    public List<WmsConfigItemVO> listByGroup(String groupCode) {
        WmsConfigGroup group = WmsConfigGroup.of(groupCode);
        Map<String, WmsSystemConfig> stored = loadStoredMap();
        List<WmsConfigItemVO> items = new ArrayList<>();
        for (WmsConfigKey key : WmsConfigKey.ofGroup(group)) {
            items.add(toItemVO(key, stored.get(key.getKey())));
        }
        return items;
    }

    @Override
    public int currentVersionNo(String key) {
        List<WmsSystemConfigHistory> rows = recentHistory(key, 1);
        return rows.isEmpty() || rows.get(0).getVersionNo() == null ? 0 : rows.get(0).getVersionNo();
    }

    @Override
    public int getInt(String key, int fallback) {
        try {
            WmsConfigKey meta = WmsConfigKey.of(key);
            WmsSystemConfig stored = this.getOne(new QueryWrapper<WmsSystemConfig>()
                    .eq("config_key", meta.getKey()), false);
            if (stored == null || stored.getConfigValue() == null || stored.getConfigValue().trim().isEmpty()) {
                return fallback;
            }
            int parsed = Integer.parseInt(stored.getConfigValue().trim());
            if (parsed <= 0) {
                log.warn("系统配置[{}]的值 {} 非正数，改用兜底值 {}", key, parsed, fallback);
                return fallback;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            // 未知键属于编程错误，但读取路径不能因此崩：记 WARN 用兜底
            log.warn("读取了未知配置键[{}]，改用兜底值 {}", key, fallback);
            return fallback;
        } catch (Exception e) {
            // 配置损坏（非数字等）不得让调用方（离线扫描任务）失败 —— 监控链路断了比阈值不准严重得多
            log.warn("系统配置[{}]的值不合法（{}），改用兜底值 {}", key, e.getMessage(), fallback);
            return fallback;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveGroup(WmsConfigSaveDTO dto) {
        if (dto == null || dto.getGroup() == null) {
            throw new IllegalArgumentException("缺少配置分组");
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (dto.getItems() != null) {
            for (WmsConfigItemDTO item : dto.getItems()) {
                if (item != null && item.getKey() != null) {
                    values.put(item.getKey(), item.getValue());
                }
            }
        }

        // 1) 先校验，全组通过才动库。校验失败时下面两个写方法一次都不会被调用。
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate(dto.getGroup(), values);
        if (!conflicts.isEmpty()) {
            throw new WmsConfigConflictException(conflicts);
        }

        // 2) upsert 主表 + 3) 追加历史（同一事务：写历史失败则 upsert 一起回滚，不会出现「值改了没留痕」）
        Map<String, WmsSystemConfig> stored = loadStoredMap();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            WmsConfigKey meta = WmsConfigKey.of(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();

            WmsSystemConfig existing = stored.get(entry.getKey());
            if (existing == null) {
                WmsSystemConfig row = new WmsSystemConfig()
                        .setConfigKey(meta.getKey())
                        .setConfigValue(value)
                        .setConfigDesc(meta.getLabel());
                WmsUtils.fillCreate(row);
                this.save(row);
            } else {
                existing.setConfigValue(value);
                WmsUtils.fillUpdate(existing);
                this.updateById(existing);
            }

            WmsSystemConfigHistory history = new WmsSystemConfigHistory();
            history.setConfigKey(meta.getKey());
            history.setConfigValue(value);
            history.setVersionNo(currentVersionNo(meta.getKey()) + 1);
            history.setChangeType(WmsConstants.CONFIG_CHANGE_SAVE);
            WmsUtils.fillCreate(history);
            wmsSystemConfigHistoryMapper.insert(history);
        }
        log.info("系统配置已保存：分组={}，项数={}", dto.getGroup(), values.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(WmsConfigRollbackDTO dto) {
        if (dto == null || dto.getGroup() == null) {
            throw new IllegalArgumentException("缺少配置分组");
        }
        WmsConfigGroup group = WmsConfigGroup.of(dto.getGroup());
        Map<String, WmsSystemConfig> stored = loadStoredMap();

        // 1) 逐键取「次新」版本（当前值是最大版本，上一版本是第 2 条）。
        //    previous 只收「确实有上一版本」的键 —— 它们才是要写回的；
        //    target 则补齐没有上一版本的键（用其当前值/默认值），它是回滚**之后的完整状态**。
        Map<String, String> previous = new LinkedHashMap<>();
        Map<String, String> target = new LinkedHashMap<>();
        for (WmsConfigKey key : WmsConfigKey.ofGroup(group)) {
            List<WmsSystemConfigHistory> rows = recentHistory(key.getKey(), 2);
            if (rows.size() >= 2) {
                String value = rows.get(1).getConfigValue();
                previous.put(key.getKey(), value);
                target.put(key.getKey(), value);
            } else {
                WmsSystemConfig current = stored.get(key.getKey());
                target.put(key.getKey(), current == null || current.getConfigValue() == null
                        ? key.getDefaultValue() : current.getConfigValue());
            }
        }
        if (previous.isEmpty()) {
            throw new RuntimeException("当前分组没有可回滚的历史版本");
        }

        // 2) 校验**回滚之后的完整状态**（而非只校验被回滚的那部分）。
        //    两条理由：① 规则中途加严时旧值可能已非法，宁可拒绝也不能把非法值写进主表；
        //    ② 若只把 previous 交给校验器，缺项会被判「配置项缺失」而报成冲突 ——
        //    那会让「组内新增了一个配置项」导致整组回滚被误拒（评审 I2）。
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate(group.getCode(), target);
        if (!conflicts.isEmpty()) {
            throw new WmsConfigConflictException(conflicts);
        }

        // 3) 写回主表 + 追加 ROLLBACK 历史（只写 previous 里的键；不删旧历史，审计链完整）
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            WmsConfigKey meta = WmsConfigKey.of(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue();

            WmsSystemConfig existing = stored.get(entry.getKey());
            if (existing == null) {
                WmsSystemConfig row = new WmsSystemConfig()
                        .setConfigKey(meta.getKey()).setConfigValue(value).setConfigDesc(meta.getLabel());
                WmsUtils.fillCreate(row);
                this.save(row);
            } else {
                existing.setConfigValue(value);
                WmsUtils.fillUpdate(existing);
                this.updateById(existing);
            }

            WmsSystemConfigHistory history = new WmsSystemConfigHistory();
            history.setConfigKey(meta.getKey());
            history.setConfigValue(value);
            history.setVersionNo(currentVersionNo(meta.getKey()) + 1);
            history.setChangeType(WmsConstants.CONFIG_CHANGE_ROLLBACK);
            WmsUtils.fillCreate(history);
            wmsSystemConfigHistoryMapper.insert(history);
        }
        log.info("系统配置已回滚：分组={}，回滚 {} 项（共 {} 项）",
                dto.getGroup(), previous.size(), target.size());
    }

    @Override
    public List<WmsSystemConfigHistory> history(String key) {
        return wmsSystemConfigHistoryMapper.selectList(new QueryWrapper<WmsSystemConfigHistory>()
                .eq("config_key", key)
                .orderByDesc("version_no", "create_time", "id"));
    }

    /**
     * 取某键最近 n 条历史（版本降序）。
     *
     * <p><b>排序必须带 tie-break</b>（version_no → create_time → id）：版本号取「最大值 + 1」，
     * 而 READ COMMITTED 下并发保存可能算出同一个版本号，只按 version_no 排序会让回滚结果不确定。
     */
    private List<WmsSystemConfigHistory> recentHistory(String key, int limit) {
        return wmsSystemConfigHistoryMapper.selectList(new QueryWrapper<WmsSystemConfigHistory>()
                .eq("config_key", key)
                .orderByDesc("version_no", "create_time", "id")
                .last("limit " + limit));
    }

    /** 一次性把在存配置读成 map，避免逐项查库。 */
    private Map<String, WmsSystemConfig> loadStoredMap() {
        Map<String, WmsSystemConfig> map = new HashMap<>();
        for (WmsSystemConfig row : this.list(new QueryWrapper<>())) {
            map.put(row.getConfigKey(), row);
        }
        return map;
    }

    private WmsConfigItemVO toItemVO(WmsConfigKey key, WmsSystemConfig stored) {
        WmsConfigItemVO vo = new WmsConfigItemVO();
        vo.setKey(key.getKey());
        vo.setLabel(key.getLabel());
        vo.setType(key.getType().name());
        // 库里没有该键时回落元数据默认值：新环境首次打开页面不能是空白
        vo.setValue(stored == null || stored.getConfigValue() == null
                ? key.getDefaultValue() : stored.getConfigValue());
        vo.setOptions(key.getOptions());
        int version = currentVersionNo(key.getKey());
        vo.setVersionNo(version);
        vo.setCanRollback(version >= 2);
        return vo;
    }
}
