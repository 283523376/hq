package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.dto.WmsConfigItemDTO;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.mapper.WmsSystemConfigHistoryMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * @Description: 系统配置读路径单测（不启 Spring、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
class WmsSystemConfigServiceImplTest {

    private static WmsSystemConfig row(String key, String value) {
        WmsSystemConfig c = new WmsSystemConfig().setConfigKey(key).setConfigValue(value);
        c.setId("id-" + key);
        return c;
    }

    /** 库里有值时用库里的值，且把版本号与「是否可回滚」一起带回。 */
    @Test
    void definitionsShouldReturnStoredValue() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.singletonList(row("inv.stock.min", "42"))).when(service).list(any(Wrapper.class));
        // 兜底桩必须在前：其余 8 个键也得有返回值，否则会走真实实现去查未注入的 mapper
        doReturn(0).when(service).currentVersionNo(any());
        doReturn(1).when(service).currentVersionNo("inv.stock.min");

        List<WmsConfigGroupVO> groups = service.definitions();

        assertEquals(4, groups.size());
        WmsConfigItemVO min = groups.get(0).getItems().stream()
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();
        assertEquals("42", min.getValue());
        assertEquals("最低库存", min.getLabel());
        assertTrue(!min.isCanRollback(), "只有一个版本时不可回滚");
    }

    /** 库里没有该键时必须回落元数据里的默认值——否则新环境一打开页面全是空。 */
    @Test
    void definitionsShouldFallBackToDefault() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        WmsConfigItemVO auto = service.definitions().stream()
                .flatMap(g -> g.getItems().stream())
                .filter(i -> "sync.auto".equals(i.getKey())).findFirst().orElseThrow();

        assertEquals("true", auto.getValue(), "无配置应回落元数据默认值");
    }

    /** 多选项要把候选值带给前端，否则下拉没得选。 */
    @Test
    void definitionsShouldCarryOptionsForMultiType() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        List<WmsConfigItemVO> all = service.definitions().stream()
                .flatMap(g -> g.getItems().stream()).toList();
        WmsConfigItemVO scope = all.stream()
                .filter(i -> "sync.scope".equals(i.getKey())).findFirst().orElseThrow();
        WmsConfigItemVO min = all.stream()
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();

        assertEquals(3, scope.getOptions().size());
        assertNull(min.getOptions());
    }

    /** listByGroup 只返回该组的项，且顺序与元数据一致。 */
    @Test
    void listByGroupShouldReturnOnlyThatGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        List<WmsConfigItemVO> items = service.listByGroup("device");

        assertEquals(2, items.size());
        assertEquals("device.offline.threshold.seconds", items.get(0).getKey());
        assertEquals("600", items.get(0).getValue(), "默认值须与既有常量同值");
    }

    /** 未知分组必须被拒。 */
    @Test
    void listByGroupShouldRejectUnknownGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());

        assertThrows(IllegalArgumentException.class, () -> service.listByGroup("nope"));
    }

    /** 版本号 ≥2 时标记为可回滚。 */
    @Test
    void definitionsShouldMarkRollbackableWhenVersionAboveOne() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());
        doReturn(2).when(service).currentVersionNo("inv.stock.min");

        WmsConfigItemVO min = service.definitions().stream().flatMap(g -> g.getItems().stream())
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();

        assertTrue(min.isCanRollback());
    }

    // ------------------------------------------------------------ 保存（BD-004 步骤 3~7）

    private static WmsConfigSaveDTO saveDto(String group, String... keyValues) {
        WmsConfigSaveDTO dto = new WmsConfigSaveDTO();
        dto.setGroup(group);
        List<WmsConfigItemDTO> items = new ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            items.add(new WmsConfigItemDTO(keyValues[i], keyValues[i + 1]));
        }
        dto.setItems(items);
        return dto;
    }

    /** 保存成功：写主表（upsert）+ 为该组每个键追加一条历史。 */
    @Test
    void save_shouldUpsertAndAppendHistory() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(0).when(service).currentVersionNo(any());

        service.saveGroup(saveDto("inventory",
                "inv.stock.min", "10", "inv.stock.safe", "50", "inv.stock.max", "200"));

        // 3 个键都是新的 → 逐键插入主表；历史同样 3 条
        verify(service, times(3)).save(any(WmsSystemConfig.class));
        ArgumentCaptor<WmsSystemConfigHistory> captor = ArgumentCaptor.forClass(WmsSystemConfigHistory.class);
        verify(historyMapper, times(3)).insert(captor.capture());
        List<WmsSystemConfigHistory> rows = captor.getAllValues();
        assertEquals("SAVE", rows.get(0).getChangeType());
        assertEquals(1, rows.get(0).getVersionNo().intValue(), "首次保存版本号为 1");
        assertEquals("10", rows.get(0).getConfigValue());
    }

    /** 已存在的键必须走更新而不是再插一行（否则会撞唯一索引）。 */
    @Test
    void save_shouldUpdateExistingRow() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.singletonList(row("inv.stock.min", "1"))).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).updateById(any(WmsSystemConfig.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(0).when(service).currentVersionNo(any());

        service.saveGroup(saveDto("inventory",
                "inv.stock.min", "10", "inv.stock.safe", "50", "inv.stock.max", "200"));

        // 已存在的键走更新，另外两个新键走插入 —— 已存在的那个绝不能变成再插一行（会撞唯一索引）
        verify(service).updateById(any(WmsSystemConfig.class));
        verify(service, times(2)).save(any(WmsSystemConfig.class));
    }

    /** 校验不过：抛冲突异常，且**一个写方法都不许调**（整体不落库）。 */
    @Test
    void save_shouldNotWriteAnythingWhenInvalid() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        WmsConfigConflictException ex = assertThrows(WmsConfigConflictException.class,
                () -> service.saveGroup(saveDto("inventory",
                        "inv.stock.min", "100", "inv.stock.safe", "50", "inv.stock.max", "200")));

        assertEquals("配置项存在冲突，请检查", ex.getMessage(), "文案必须逐字对齐蓝图原文");
        verify(service, never()).save(any(WmsSystemConfig.class));
        verify(service, never()).updateById(any(WmsSystemConfig.class));
        verify(historyMapper, never()).insert(any(WmsSystemConfigHistory.class));
    }

    /** 冲突异常必须带明细——只给一句话用户无从下手（Review Focus 5）。 */
    @Test
    void save_shouldCarryConflictsPayload() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", mock(WmsSystemConfigHistoryMapper.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        WmsConfigConflictException ex = assertThrows(WmsConfigConflictException.class,
                () -> service.saveGroup(saveDto("inventory",
                        "inv.stock.min", "100", "inv.stock.safe", "50", "inv.stock.max", "200")));

        assertTrue(!ex.getConflicts().isEmpty(), "conflicts 明细不得为空");
        assertEquals("inv.stock.min", ex.getConflicts().get(0).getKey());
    }

    /** 缺项也必须整组拒绝（保存是全组提交）。 */
    @Test
    void save_shouldRejectIncompleteGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", mock(WmsSystemConfigHistoryMapper.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        assertThrows(WmsConfigConflictException.class,
                () -> service.saveGroup(saveDto("inventory", "inv.stock.min", "10")));
    }

    // ------------------------------------------------------------ 回滚与历史（BD-004 异常处理）

    private static WmsSystemConfigHistory hist(String key, String value, int version, String type) {
        WmsSystemConfigHistory h = new WmsSystemConfigHistory();
        h.setConfigKey(key);
        h.setConfigValue(value);
        h.setVersionNo(version);
        h.setChangeType(type);
        return h;
    }

    /** 回滚取「次新」版本写回主表，并追加一条 ROLLBACK 历史（不删旧历史）。 */
    @Test
    void rollback_shouldRestorePreviousVersionAndAppendHistory() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(0).when(service).currentVersionNo(any());
        // 该键的历史：v2 当前值 60，v1 上一版本 50
        doReturn(new ArrayList<>(List.of(
                hist("device.offline.threshold.seconds", "60", 2, "SAVE"),
                hist("device.offline.threshold.seconds", "50", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("device");
        service.rollback(dto);

        ArgumentCaptor<WmsSystemConfig> captor = ArgumentCaptor.forClass(WmsSystemConfig.class);
        verify(service, times(2)).save(captor.capture());
        assertEquals("50", captor.getAllValues().get(0).getConfigValue(), "应写回上一版本 50，而不是当前值 60");

        ArgumentCaptor<WmsSystemConfigHistory> hCaptor = ArgumentCaptor.forClass(WmsSystemConfigHistory.class);
        verify(historyMapper, times(2)).insert(hCaptor.capture());
        assertEquals("ROLLBACK", hCaptor.getAllValues().get(0).getChangeType());
    }

    /** 只有 1 个版本的键没有可回滚版本——跳过它，不能报错也不能凭空造一个值。 */
    @Test
    void rollback_shouldSkipKeysWithoutPreviousVersion() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(new ArrayList<>(List.of(hist("device.offline.threshold.seconds", "60", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("device");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.rollback(dto));

        assertTrue(ex.getMessage().contains("没有可回滚"), "应给出与「冲突」不同的提示，实际：" + ex.getMessage());
        verify(service, never()).save(any(WmsSystemConfig.class));
    }

    /** 回滚目标值若过不了当前校验（规则中途加严），必须拒绝，不得先把非法值写进库（Review Focus 3）。 */
    @Test
    void rollback_shouldRejectWhenPreviousValueInvalid() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        // 历史里 v1 是 -5（当年合法），现在库存不允许负数
        doReturn(new ArrayList<>(List.of(
                hist("inv.stock.min", "10", 2, "SAVE"),
                hist("inv.stock.min", "-5", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("inventory");
        WmsConfigConflictException ex = assertThrows(WmsConfigConflictException.class, () -> service.rollback(dto));

        assertEquals("配置项存在冲突，请检查", ex.getMessage());
        verify(service, never()).save(any(WmsSystemConfig.class));
        verify(service, never()).updateById(any(WmsSystemConfig.class));
    }

    /**
     * 历史排序必须带 tie-break：并发保存可能产生重复版本号，回滚结果仍须确定（Review Focus 1）。
     * 断言 ORDER BY 里出现 create_time —— 只有 version_no 的话这条会红。
     */
    @Test
    void history_shouldOrderDeterministicallyOnDuplicateVersion() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(historyMapper).selectList(any(Wrapper.class));

        service.history("inv.stock.min");

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(historyMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("version_no"), "应按版本号排序，实际：" + sql);
        assertTrue(sql.contains("create_time"), "必须带 tie-break（create_time）以保证重复版本号下结果确定，实际：" + sql);
    }

    /** 历史查询按版本降序返回（透传 mapper 结果）。 */
    @Test
    void history_shouldReturnRowsFromMapper() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(new ArrayList<>(List.of(
                hist("device.offline.threshold.seconds", "60", 2, "SAVE"),
                hist("device.offline.threshold.seconds", "50", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        assertEquals(2, service.history("device.offline.threshold.seconds").size());
    }

    // ------------------------------------------------------------ getInt 兜底（Review Focus 4）

    private WmsSystemConfigServiceImpl intService(String storedValue) {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(storedValue == null ? null : row("device.offline.threshold.seconds", storedValue))
                .when(service).getOne(any(Wrapper.class), anyBoolean());
        return service;
    }

    /** 库里有合法值时用它。 */
    @Test
    void getInt_shouldUseStoredValue() {
        assertEquals(60, intService("60").getInt("device.offline.threshold.seconds", 600));
    }

    /** 行不存在 → 兜底。 */
    @Test
    void getInt_shouldFallBackWhenMissing() {
        assertEquals(600, intService(null).getInt("device.offline.threshold.seconds", 600));
    }

    /** 空值/空白 → 兜底（不抛）。 */
    @Test
    void getInt_shouldFallBackWhenBlank() {
        assertEquals(600, intService("   ").getInt("device.offline.threshold.seconds", 600));
    }

    /** 非数字 → 兜底（不抛）。配置写错不能让离线扫描任务崩掉。 */
    @Test
    void getInt_shouldFallBackWhenNotNumeric() {
        assertEquals(600, intService("abc").getInt("device.offline.threshold.seconds", 600));
    }

    /** 非正数 → 兜底（0 秒阈值会把所有设备判离线）。 */
    @Test
    void getInt_shouldFallBackWhenNonPositive() {
        assertEquals(600, intService("0").getInt("device.offline.threshold.seconds", 600));
        assertEquals(600, intService("-5").getInt("device.offline.threshold.seconds", 600));
    }

    /** 未知键 → 兜底（不抛 IllegalArgumentException）。 */
    @Test
    void getInt_shouldFallBackOnUnknownKey() {
        assertEquals(600, intService("60").getInt("no.such.key", 600));
    }
}
