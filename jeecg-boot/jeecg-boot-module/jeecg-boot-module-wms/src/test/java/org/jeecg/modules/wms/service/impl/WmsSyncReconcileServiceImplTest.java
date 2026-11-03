package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeeInventoryReader;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsSyncAggregateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 对账执行服务单测（建批次 / 汇总 / 重试 / 比对 / 差异落库 / 告警）。
 *
 * <p>全部依赖 mock：mapper、金蝶 Reader、同步日志、配置、站内消息；{@code BACKOFF_MS} 置 0。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncReconcileServiceImplTest {

    private static final String RUN_ID = "RUN1";
    private static final String RUN_NO = "SB20261103120000001";
    private static final String OPERATOR = "zhangsan";

    private WmsInventoryMapper inventoryMapper;
    private WmsSyncSnapshotMapper snapshotMapper;
    private WmsSyncFlowMapper flowMapper;
    private WmsSyncDiffMapper diffMapper;
    private WmsSyncRunMapper runMapper;
    private IWmsKingdeeInventoryReader inventoryReader;
    private IWmsSyncLogService syncLogService;
    private IWmsSystemConfigService configService;
    private WmsKingdeeProperties kingdeeProperties;
    private ISysBaseAPI sysBaseAPI;
    private WmsSyncReconcileServiceImpl service;

    private long[] originalBackoff;

    @BeforeEach
    void setUp() {
        originalBackoff = WmsSyncReconcileServiceImpl.BACKOFF_MS;
        WmsSyncReconcileServiceImpl.BACKOFF_MS = new long[]{0L, 0L, 0L};

        inventoryMapper = mock(WmsInventoryMapper.class);
        snapshotMapper = mock(WmsSyncSnapshotMapper.class);
        flowMapper = mock(WmsSyncFlowMapper.class);
        diffMapper = mock(WmsSyncDiffMapper.class);
        runMapper = mock(WmsSyncRunMapper.class);
        inventoryReader = mock(IWmsKingdeeInventoryReader.class);
        syncLogService = mock(IWmsSyncLogService.class);
        configService = mock(IWmsSystemConfigService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        kingdeeProperties = new WmsKingdeeProperties();
        kingdeeProperties.setEnabled(true);

        service = new WmsSyncReconcileServiceImpl(inventoryMapper, snapshotMapper, flowMapper, diffMapper,
                inventoryReader, syncLogService, configService, kingdeeProperties, sysBaseAPI);
        WmsTestSupport.inject(service, "baseMapper", runMapper);
        service.setAlertToUserForTest("admin");
        service.setExecutorForTest(Runnable::run);
    }

    @AfterEach
    void tearDown() {
        WmsSyncReconcileServiceImpl.BACKOFF_MS = originalBackoff;
    }

    // ================================================================ 成功路径

    @Test
    void executeRun_equalRows_successWithoutDiff() {
        Date now = new Date();
        WmsSyncRun run = run(WmsConstants.SYNC_SCOPE_FULL, now, now);
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(inventoryMapper.selectReconcileAggregate(any(), any())).thenReturn(List.of(
                aggregate("D.01", "WMS-A", "M1", "L1", "10"),
                aggregate("D.01", "WMS-A", "M2", "L2", "20")));
        when(inventoryReader.queryInventory(any())).thenReturn(map(
                "D.01|M1|L1", "10",
                "D.01|M2|L2", "20.000000"));

        service.executeRunForTest(RUN_ID);

        // 快照先落库，比对结果就地回写
        ArgumentCaptor<WmsSyncSnapshot> insertCap = ArgumentCaptor.forClass(WmsSyncSnapshot.class);
        verify(snapshotMapper, times(2)).insert(insertCap.capture());
        assertEquals(OPERATOR, insertCap.getAllValues().get(0).getCreateBy());
        assertEquals(OPERATOR, insertCap.getAllValues().get(1).getCreateBy());

        ArgumentCaptor<WmsSyncSnapshot> updateCap = ArgumentCaptor.forClass(WmsSyncSnapshot.class);
        verify(snapshotMapper, times(2)).updateById(updateCap.capture());
        for (WmsSyncSnapshot row : updateCap.getAllValues()) {
            assertEquals(WmsConstants.SNAPSHOT_MATCH_EQUAL, row.getMatchStatus());
            assertEquals(0, row.getDiffQty().compareTo(BigDecimal.ZERO));
        }

        verify(diffMapper, never()).insert(any(WmsSyncDiff.class));

        WmsSyncRun finished = lastRunUpdate();
        assertEquals(WmsConstants.SYNC_RUN_STATUS_SUCCESS, finished.getStatus());
        assertEquals(2, finished.getSnapshotCount().intValue());
        assertEquals(0, finished.getDiffCount().intValue());
        assertNotNull(finished.getCostMs());
        assertEquals(OPERATOR, finished.getUpdateBy());

        verify(syncLogService, times(1)).logSync(anyString(), anyString(), anyString(), anyString(),
                eq(true), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    void executeRun_readerReceivesErpWarehouseCode() {
        Date now = new Date();
        when(runMapper.selectById(RUN_ID)).thenReturn(run(WmsConstants.SYNC_SCOPE_FULL, now, now));
        when(inventoryMapper.selectReconcileAggregate(any(), any())).thenReturn(List.of(
                aggregate("D.01", "WMS-A", "M1", "L1", "10")));
        when(inventoryReader.queryInventory(any())).thenReturn(map("D.01|M1|L1", "10"));

        service.executeRunForTest(RUN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> warehouses = ArgumentCaptor.forClass(List.class);
        verify(inventoryReader).queryInventory(warehouses.capture());
        assertEquals(List.of("D.01"), warehouses.getValue());
        assertFalse(warehouses.getValue().contains("WMS-A"), "传给 Reader 的必须是 erpStockCode，不是 WMS 编码");
    }

    // ================================================================ 差异

    @Test
    void executeRun_buildsFourDiffTypes() {
        Date now = new Date();
        WmsSyncRun run = run(WmsConstants.SYNC_SCOPE_FULL, now, now);
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(inventoryMapper.selectReconcileAggregate(any(), any())).thenReturn(List.of(
                aggregate("D.01", "WMS-A", "M1", "L1", "10"),   // ERP 8  → 多账 +2
                aggregate("D.01", "WMS-A", "M2", "L2", "5"),    // ERP 9  → 少账 -4
                aggregate("D.01", "WMS-A", "M3", "L3", "7"),    // ERP 无 → 仅WMS
                aggregate("D.01", "WMS-A", "M5", "L5", "2")));  // ERP 2  → 一致，不入差异
        when(inventoryReader.queryInventory(any())).thenReturn(map(
                "D.01|M1|L1", "8",
                "D.01|M2|L2", "9",
                "D.01|M5|L5", "2",
                "D.01|M4|L4", "3"));                            // WMS 无 → 仅ERP

        service.executeRunForTest(RUN_ID);

        ArgumentCaptor<WmsSyncDiff> diffCap = ArgumentCaptor.forClass(WmsSyncDiff.class);
        verify(diffMapper, times(4)).insert(diffCap.capture());
        Map<String, WmsSyncDiff> diffs = diffCap.getAllValues().stream()
                .collect(Collectors.toMap(WmsSyncDiff::getMaterialCode, d -> d));

        assertEquals(WmsConstants.DIFF_TYPE_MORE, diffs.get("M1").getDiffType());
        assertEquals(0, diffs.get("M1").getDiffQty().compareTo(BigDecimal.valueOf(2)));
        assertEquals(WmsConstants.DIFF_STATUS_PENDING, diffs.get("M1").getStatus());
        assertEquals(RUN_ID, diffs.get("M1").getRunId());
        assertEquals(OPERATOR, diffs.get("M1").getCreateBy());

        assertEquals(WmsConstants.DIFF_TYPE_LESS, diffs.get("M2").getDiffType());
        assertEquals(0, diffs.get("M2").getDiffQty().compareTo(BigDecimal.valueOf(-4)));

        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, diffs.get("M3").getDiffType());
        assertEquals(0, diffs.get("M3").getErpQty().compareTo(BigDecimal.ZERO));
        assertEquals(0, diffs.get("M3").getDiffQty().compareTo(BigDecimal.valueOf(7)));

        assertEquals(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY, diffs.get("M4").getDiffType());
        assertEquals(0, diffs.get("M4").getWmsQty().compareTo(BigDecimal.ZERO));
        assertEquals(0, diffs.get("M4").getErpQty().compareTo(BigDecimal.valueOf(3)));
        assertEquals(0, diffs.get("M4").getDiffQty().compareTo(BigDecimal.valueOf(-3)));

        // 仅ERP 行也被落成快照（预置 仅ERP 标记 + wmsQty=0），且批次计数含它
        ArgumentCaptor<WmsSyncSnapshot> snapshotCap = ArgumentCaptor.forClass(WmsSyncSnapshot.class);
        verify(snapshotMapper, atLeastTimes(5)).insert(snapshotCap.capture());
        WmsSyncSnapshot erpOnly = snapshotCap.getAllValues().stream()
                .filter(row -> "M4".equals(row.getMaterialCode()))
                .findFirst().orElseThrow();
        assertEquals(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY, erpOnly.getMatchStatus());
        assertEquals(0, erpOnly.getWmsQty().compareTo(BigDecimal.ZERO));

        WmsSyncRun finished = lastRunUpdate();
        assertEquals(WmsConstants.SYNC_RUN_STATUS_SUCCESS, finished.getStatus());
        assertEquals(5, finished.getSnapshotCount().intValue());
        assertEquals(4, finished.getDiffCount().intValue());
    }

    // ================================================================ 重试

    @Test
    void executeRun_readerAlwaysFails_retriesFourTimesAndKeepsSnapshots() {
        Date now = new Date();
        WmsSyncRun run = run(WmsConstants.SYNC_SCOPE_FULL, now, now);
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(inventoryMapper.selectReconcileAggregate(any(), any())).thenReturn(List.of(
                aggregate("D.01", "WMS-A", "M1", "L1", "10"),
                aggregate("D.01", "WMS-A", "M2", "L2", "20")));
        when(inventoryReader.queryInventory(any())).thenThrow(new RuntimeException("金蝶超时"));

        service.executeRunForTest(RUN_ID);

        verify(inventoryReader, times(4)).queryInventory(any());

        ArgumentCaptor<Integer> retryCap = ArgumentCaptor.forClass(Integer.class);
        verify(syncLogService, times(3)).logSync(anyString(), anyString(), anyString(), anyString(),
                eq(false), retryCap.capture(), any(), any(), any(), anyLong());
        assertEquals(List.of(1, 2, 3), retryCap.getAllValues());

        // 快照先落库，查询失败也保留证据
        verify(snapshotMapper, times(2)).insert(any(WmsSyncSnapshot.class));
        verify(diffMapper, never()).insert(any(WmsSyncDiff.class));

        WmsSyncRun failed = lastRunUpdate();
        assertEquals(WmsConstants.SYNC_RUN_STATUS_FAILED, failed.getStatus());
        assertTrue(failed.getErrorMessage().contains("金蝶超时"));
        assertEquals(3, failed.getRetryCount().intValue());

        verify(sysBaseAPI, times(1)).sendSysAnnouncement(any());
    }

    // ================================================================ TODAY 口径

    @Test
    void executeRun_todayIntersectsErpAndNeverBuildsErpOnlyRows() {
        Date now = new Date();
        Date todayStart = startOfDay(now);
        WmsSyncRun run = run(WmsConstants.SYNC_SCOPE_TODAY, todayStart, now);
        run.setOperator("lisi");
        when(runMapper.selectById(RUN_ID)).thenReturn(run);
        when(inventoryMapper.selectReconcileAggregate(any(), any())).thenReturn(List.of(
                aggregate("D.01", "WMS-A", "M1", "L1", "10"),
                aggregate("D.01", "WMS-A", "M2", "L2", "20")));
        when(inventoryReader.queryInventory(any())).thenReturn(map(
                "D.01|M1|L1", "10",
                "D.01|M2|L2", "20",
                "D.01|M9|L9", "99"));   // ERP 多出的 key，TODAY 口径必须被丢弃

        service.executeRunForTest(RUN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> erpWarehouses = ArgumentCaptor.forClass(List.class);
        verify(inventoryReader).queryInventory(erpWarehouses.capture());
        assertEquals(List.of("D.01"), erpWarehouses.getValue());

        verify(diffMapper, never()).insert(any(WmsSyncDiff.class));
        WmsSyncRun finished = lastRunUpdate();
        assertEquals(WmsConstants.SYNC_RUN_STATUS_SUCCESS, finished.getStatus());
        assertEquals(2, finished.getSnapshotCount().intValue());
        assertEquals(0, finished.getDiffCount().intValue());

        // operator 透传：快照的 createBy/updateBy 必须是 run.operator，不依赖 Shiro
        ArgumentCaptor<WmsSyncSnapshot> snapshotCap = ArgumentCaptor.forClass(WmsSyncSnapshot.class);
        verify(snapshotMapper, times(2)).insert(snapshotCap.capture());
        for (WmsSyncSnapshot row : snapshotCap.getAllValues()) {
            assertEquals("lisi", row.getCreateBy());
            assertEquals("lisi", row.getUpdateBy());
        }

        // 今日变动窗口 = 当天 00:00，仓库过滤为空
        ArgumentCaptor<Date> windowCap = ArgumentCaptor.forClass(Date.class);
        ArgumentCaptor<List<String>> aggregateWarehouses = ArgumentCaptor.forClass(List.class);
        verify(inventoryMapper).selectReconcileAggregate(windowCap.capture(), aggregateWarehouses.capture());
        assertEquals(todayStart, windowCap.getValue());
        assertNull(aggregateWarehouses.getValue());
    }

    // ================================================================ 建批次

    @Test
    void createRun_warehouseScopeWithoutCodes_isRejected() {
        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType(WmsConstants.SYNC_SCOPE_WAREHOUSE);
        dto.setWarehouseCodes(List.of("  ", ""));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createRun(dto, OPERATOR));
        assertTrue(e.getMessage().contains("至少一个仓库"));
        verify(runMapper, never()).insert(any(WmsSyncRun.class));
    }

    @Test
    void createRun_rejectsWhenAnotherRunIsRunning() {
        when(runMapper.selectCount(any())).thenReturn(1L);
        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType(WmsConstants.SYNC_SCOPE_FULL);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createRun(dto, OPERATOR));
        assertEquals("已有对账批次在执行中，请稍后再试", e.getMessage());
        verify(runMapper, never()).insert(any(WmsSyncRun.class));
    }

    @Test
    void createRun_usesProvidedWindowStartAndPersistsOperator() {
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.selectById(anyString())).thenReturn(null);
        Date watermark = new Date(System.currentTimeMillis() - 3600_000L);
        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType(WmsConstants.SYNC_SCOPE_FULL);
        dto.setWindowStart(watermark);

        String runId = service.createRun(dto, OPERATOR);

        ArgumentCaptor<WmsSyncRun> cap = ArgumentCaptor.forClass(WmsSyncRun.class);
        verify(runMapper).insert(cap.capture());
        WmsSyncRun saved = cap.getValue();
        assertNotNull(runId);
        assertEquals(runId, saved.getId());
        assertTrue(saved.getRunNo().startsWith("SB"));
        assertEquals(19, saved.getRunNo().length(), "批次号 = SB + 14 位时间 + 3 位随机");
        assertEquals(WmsConstants.SYNC_RUN_STATUS_RUNNING, saved.getStatus());
        assertEquals(WmsConstants.SYNC_SCOPE_FULL, saved.getScopeType());
        assertEquals(watermark, saved.getWindowStart());
        assertNotNull(saved.getWindowEnd());
        assertEquals(OPERATOR, saved.getOperator());
        assertEquals(OPERATOR, saved.getCreateBy());
        assertNull(saved.getWarehouseCodes());
    }

    @Test
    void createRun_invalidScope_isRejected() {
        WmsSyncRunCreateDTO dto = new WmsSyncRunCreateDTO();
        dto.setScopeType("SOMETHING");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.createRun(dto, OPERATOR));
        assertTrue(e.getMessage().contains("不支持的对账范围"));
    }

    // ================================================================ 工具

    private WmsSyncRun run(String scope, Date windowStart, Date windowEnd) {
        // setId 定义在 JeecgEntity，链式返回父类型，故不能与子类 setter 串成一行
        WmsSyncRun run = new WmsSyncRun();
        run.setId(RUN_ID);
        run.setRunNo(RUN_NO);
        run.setScopeType(scope);
        run.setWindowStart(windowStart);
        run.setWindowEnd(windowEnd);
        run.setStatus(WmsConstants.SYNC_RUN_STATUS_RUNNING);
        run.setOperator(OPERATOR);
        run.setSnapshotCount(0);
        run.setDiffCount(0);
        run.setFlowCount(0);
        run.setRetryCount(0);
        return run;
    }

    private WmsSyncAggregateVO aggregate(String erpWarehouse, String wmsWarehouse,
                                         String erpMaterial, String batch, String qty) {
        WmsSyncAggregateVO vo = new WmsSyncAggregateVO();
        vo.setErpWarehouseCode(erpWarehouse);
        vo.setWarehouseCode(wmsWarehouse);
        vo.setErpMaterialCode(erpMaterial);
        vo.setMaterialCode(erpMaterial);
        vo.setMaterialName("物料" + erpMaterial);
        vo.setUnit("PCS");
        vo.setBatchNo(batch);
        vo.setQty(new BigDecimal(qty));
        vo.setLockedQty(BigDecimal.ZERO);
        return vo;
    }

    private Map<String, BigDecimal> map(String... keyValues) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            result.put(keyValues[i], new BigDecimal(keyValues[i + 1]));
        }
        return result;
    }

    private WmsSyncRun lastRunUpdate() {
        ArgumentCaptor<WmsSyncRun> cap = ArgumentCaptor.forClass(WmsSyncRun.class);
        verify(runMapper, org.mockito.Mockito.atLeastOnce()).updateById(cap.capture());
        List<WmsSyncRun> all = new ArrayList<>(cap.getAllValues());
        return all.get(all.size() - 1);
    }

    private static org.mockito.verification.VerificationMode atLeastTimes(int times) {
        return org.mockito.Mockito.atLeast(times);
    }

    private static Date startOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
}
