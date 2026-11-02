package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.dto.WmsConfigItemDTO;
import org.jeecg.modules.wms.dto.WmsConfigRollbackDTO;
import org.jeecg.modules.wms.dto.WmsConfigSaveDTO;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jeecg.modules.wms.constant.WmsKingdeeSyncScope;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.entity.WmsSystemConfigHistory;
import org.jeecg.modules.wms.exception.WmsConfigConflictException;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsSystemConfigHistoryMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
        doReturn(java.util.Map.of("inv.stock.min", 1)).when(service).latestVersionNoMap(any());

        List<WmsConfigGroupVO> groups = service.definitions();

        // 5 组：库存 / 同步 / 不良品 / 设备 / 审计（XT-002 新增，保留期前端可调）
        assertEquals(5, groups.size());
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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

        List<WmsConfigItemVO> all = service.definitions().stream()
                .flatMap(g -> g.getItems().stream()).toList();
        WmsConfigItemVO scope = all.stream()
                .filter(i -> "sync.scope".equals(i.getKey())).findFirst().orElseThrow();
        WmsConfigItemVO min = all.stream()
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();

        assertEquals(WmsKingdeeSyncScope.labels(), scope.getOptions(),
                "候选值必须与同步范围登记表一致（加范围只改登记表）");
        assertNull(min.getOptions());
    }

    /** listByGroup 只返回该组的项，且顺序与元数据一致。 */
    @Test
    void listByGroupShouldReturnOnlyThatGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
        doReturn(java.util.Map.of("inv.stock.min", 2)).when(service).latestVersionNoMap(any());

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

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
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
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

    // ------------------------------------------------------------ getString 兜底

    @Test
    void getString_shouldUseStoredValue() {
        assertEquals("FPC01", intService("FPC01").getString("defect.warehouse.code", ""));
    }

    @Test
    void getString_shouldFallBackWhenBlank() {
        assertEquals("fallback", intService("   ").getString("defect.warehouse.code", "fallback"));
    }

    @Test
    void getString_shouldFallBackOnUnknownKey() {
        assertEquals("fallback", intService("FPC01").getString("no.such.key", "fallback"));
    }

    /**
     * 组内只有部分键有上一版本时：**跳过没有的那个**，其余照常回滚——
     * 不能因为「有一个键没有上一版本」就把整组判成冲突（评审 I2）。
     *
     * <p>真实触发：往既有分组新增一个配置项（枚举加一项）后，新键只有 v1、老键已在 v2+，
     * 此时「回滚」按钮可点（canRollback 取 some），点下去必须能回滚老键、而不是报假冲突。
     *
     * <p>stub 用顺序返回，依赖 ofGroup 的枚举声明顺序（offline → throttle），确定性成立。
     */
    @Test
    void rollback_shouldSkipKeysWithoutPreviousVersionButRollBackTheRest() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        // 两个键当前都有值
        doReturn(new ArrayList<>(List.of(
                row("device.offline.threshold.seconds", "60"),
                row("device.alert.throttle.minutes", "30")))).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).updateById(any(WmsSystemConfig.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
        doReturn(new ArrayList<>(List.of(
                hist("device.offline.threshold.seconds", "60", 2, "SAVE"),
                hist("device.offline.threshold.seconds", "50", 1, "SAVE"))))
                .doReturn(new ArrayList<>(List.of(
                        hist("device.alert.throttle.minutes", "30", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("device");
        service.rollback(dto);

        ArgumentCaptor<WmsSystemConfig> captor = ArgumentCaptor.forClass(WmsSystemConfig.class);
        verify(service, times(1)).updateById(captor.capture());
        assertEquals("device.offline.threshold.seconds", captor.getValue().getConfigKey());
        assertEquals("50", captor.getValue().getConfigValue(), "只有「有上一版本」的键被回滚");
        ArgumentCaptor<WmsSystemConfigHistory> hCaptor = ArgumentCaptor.forClass(WmsSystemConfigHistory.class);
        verify(historyMapper, times(1)).insert(hCaptor.capture());
        assertEquals("ROLLBACK", hCaptor.getAllValues().get(0).getChangeType());
    }

    // ------------------------------------------------------------ 遗留 Minor（评审 M1 / M2 / M5 / M6）

    /** 捕获本类日志，用于断言 WARN 文案（评审 M1/M2：兜底必须留痕且不得张冠李戴）。 */
    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WmsSystemConfigServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(WmsSystemConfigServiceImpl.class)).detachAppender(appender);
    }

    private static boolean warnedAbout(ListAppender<ILoggingEvent> appender, String keyword) {
        return appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN && e.getFormattedMessage().contains(keyword));
    }

    /**
     * 值非法（非数字）时 WARN 必须说「值不合法」，**不能**报成「未知配置键」（M1）。
     *
     * <p>`NumberFormatException extends IllegalArgumentException`，catch 顺序写反就会张冠李戴，
     * 排查时会被引到完全错误的方向。
     */
    @Test
    void getInt_shouldWarnAboutBadValueNotUnknownKey() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            intService("abc").getInt("device.offline.threshold.seconds", 600);

            assertTrue(warnedAbout(appender, "不合法"), "应报「值不合法」，实际日志：" + appender.list);
            assertTrue(!warnedAbout(appender, "未知配置键"), "不得报成「未知配置键」，实际日志：" + appender.list);
        } finally {
            detachAppender(appender);
        }
    }

    /** 未知键仍报「未知配置键」（M1 的另一半：修 catch 顺序不能把这条弄丢）。 */
    @Test
    void getInt_shouldWarnAboutUnknownKey() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            intService("60").getInt("no.such.key", 600);
            assertTrue(warnedAbout(appender, "未知配置键"), "实际日志：" + appender.list);
        } finally {
            detachAppender(appender);
        }
    }

    /** 行不存在 / 值为空也必须打 WARN —— 接口 Javadoc 与 spec §6 都承诺「读不到→回落并打 WARN」（M2）。 */
    @Test
    void getInt_shouldWarnWhenRowMissingOrBlank() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            intService(null).getInt("device.offline.threshold.seconds", 600);
            intService("   ").getInt("device.offline.threshold.seconds", 600);

            assertEquals(2, appender.list.stream()
                            .filter(e -> e.getLevel() == Level.WARN).count(),
                    "两条兜底路径各应留一条 WARN，实际日志：" + appender.list);
        } finally {
            detachAppender(appender);
        }
    }

    /** group 带首尾空格必须能用，与 list/history 的入参处理一致（M5）。 */
    @Test
    void saveGroup_shouldTrimGroupCode() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", mock(WmsSystemConfigHistoryMapper.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

        // 不该抛「未知配置分组：  inventory  」
        service.saveGroup(saveDto("  inventory  ",
                "inv.stock.min", "10", "inv.stock.safe", "50", "inv.stock.max", "200"));

        verify(service, times(3)).save(any(WmsSystemConfig.class));
    }

    /** 回滚同样要能接受带空格的 group（M5）。 */
    @Test
    void rollback_shouldTrimGroupCode() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
        doReturn(new ArrayList<>(List.of(
                hist("device.offline.threshold.seconds", "60", 2, "SAVE"),
                hist("device.offline.threshold.seconds", "50", 1, "SAVE"))))
                .when(historyMapper).selectList(any(Wrapper.class));

        WmsConfigRollbackDTO dto = new WmsConfigRollbackDTO();
        dto.setGroup("  device  ");
        service.rollback(dto);

        verify(service, times(2)).save(any(WmsSystemConfig.class));
    }

    /**
     * 多选值落库前必须规范化（段内 trim + 去空段后重新拼接）（M6）。
     *
     * <p>原样存 "A , B"（A/B 为同步范围文案）会让前端 `split(',')` 得到带空格的幽灵标签。
     */
    @Test
    void saveGroup_shouldNormalizeMultiValueBeforeStoring() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", mock(WmsSystemConfigHistoryMapper.class));
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

        String first = WmsKingdeeSyncScope.labels().get(0);
        String second = WmsKingdeeSyncScope.labels().get(1);
        service.saveGroup(saveDto("sync",
                "sync.scope", first + " , " + second, "sync.auto", "true"));

        ArgumentCaptor<WmsSystemConfig> captor = ArgumentCaptor.forClass(WmsSystemConfig.class);
        verify(service, times(2)).save(captor.capture());
        WmsSystemConfig scopeRow = captor.getAllValues().stream()
                .filter(r -> "sync.scope".equals(r.getConfigKey())).findFirst().orElseThrow();
        assertEquals(first + "," + second, scopeRow.getConfigValue(), "段内空格必须被规范化掉");
    }

    // ------------------------------------------------- 不良品默认仓：单选候选项（2026-10-27）

    /** 造两个启用仓区，只有 FPC01 带 FPC-DEFAULT 库位。 */
    private void stubWarehouses(WmsSystemConfigServiceImpl service) {
        WmsWarehouseMapper warehouseMapper = mock(WmsWarehouseMapper.class);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsTestSupport.inject(service, "warehouseMapper", warehouseMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);

        WmsWarehouse fpc = new WmsWarehouse();
        fpc.setId("w-fpc");
        fpc.setWarehouseCode("FPC01");
        fpc.setWarehouseName("废品仓");
        WmsWarehouse other = new WmsWarehouse();
        other.setId("w-other");
        other.setWarehouseCode("MJ01");
        other.setWarehouseName("敏捷仓");
        doReturn(List.of(fpc, other)).when(warehouseMapper).selectList(any());

        WmsLocation location = new WmsLocation();
        location.setWarehouseId("w-fpc");
        doReturn(List.of(location)).when(locationMapper).selectList(any());
    }

    /** 全部启用仓区都列出来；缺 FPC-DEFAULT 库位的置灰并注明原因（选了也不会生效）。 */
    @Test
    void defectWarehouseOptions_shouldListAllEnabledWarehousesAndDisableUnusable() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());
        stubWarehouses(service);

        List<WmsConfigItemVO> items = service.listByGroup("defect");

        assertEquals(1, items.size(), "「不良品是否入线边柜」已下线，本组只剩一项");
        WmsConfigItemVO item = items.get(0);
        assertEquals("defect.warehouse.code", item.getKey());
        assertEquals("SELECT", item.getType());
        assertEquals(2, item.getOptionItems().size(), "启用仓区都要列出来，否则用户会以为仓区丢了");
        assertEquals("FPC01", item.getOptionItems().get(0).getValue());
        assertEquals("FPC01 废品仓", item.getOptionItems().get(0).getLabel());
        assertTrue(!Boolean.TRUE.equals(item.getOptionItems().get(0).getDisabled()), "带默认库位，可选");
        assertEquals("MJ01", item.getOptionItems().get(1).getValue());
        assertTrue(item.getOptionItems().get(1).getLabel().contains("缺少 FPC-DEFAULT"),
                "缺默认库位要说清楚原因，实际：" + item.getOptionItems().get(1).getLabel());
        assertTrue(Boolean.TRUE.equals(item.getOptionItems().get(1).getDisabled()), "缺默认库位，置灰");
    }

    /** 直接调接口塞一个不存在的仓区编码：必须判冲突，不能静默写入后运行时回退。 */
    @Test
    void save_shouldRejectUnknownDefectWarehouse() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        stubWarehouses(service);

        WmsConfigConflictException e = assertThrows(WmsConfigConflictException.class,
                () -> service.saveGroup(saveDto("defect", "defect.warehouse.code", "NOPE")));

        assertTrue(e.getMessage().contains("NOPE") || e.getConflicts().get(0).getReason().contains("NOPE"),
                "冲突文案要带出用户填的值，否则不知道错在哪");
        verify(service, never()).save(any(WmsSystemConfig.class));
    }

    /** 留空是合法值：沿用废品仓默认库位。 */
    @Test
    void save_shouldAcceptBlankDefectWarehouse() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        WmsSystemConfigHistoryMapper historyMapper = mock(WmsSystemConfigHistoryMapper.class);
        WmsTestSupport.inject(service, "wmsSystemConfigHistoryMapper", historyMapper);
        stubWarehouses(service);
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(true).when(service).save(any(WmsSystemConfig.class));
        doReturn(java.util.Collections.<String, Integer>emptyMap()).when(service).latestVersionNoMap(any());

        service.saveGroup(saveDto("defect", "defect.warehouse.code", ""));

        verify(service).save(any(WmsSystemConfig.class));
    }
}
