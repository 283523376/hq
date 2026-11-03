package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsSyncRunCreateDTO;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.service.IWmsSyncReconcileService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobKey;
import org.quartz.Scheduler;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 定时对账任务（spec §2.2）。
 *
 * <p>定时任务没有 UI，配错范围的唯一后果是「每天静默跑错口径」，所以这里把三个闸门都钉死：
 * {@code sync.auto}、{@code WAREHOUSE}（无仓库上下文必须跳过）、水位线只对 TODAY 生效。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncReconcileJobTest {

    private IWmsSystemConfigService systemConfigService;
    private IWmsSyncReconcileService reconcileService;
    private WmsSyncRunMapper syncRunMapper;

    @BeforeEach
    void setUp() {
        systemConfigService = mock(IWmsSystemConfigService.class);
        reconcileService = mock(IWmsSyncReconcileService.class);
        syncRunMapper = mock(WmsSyncRunMapper.class);
        when(systemConfigService.getBool(WmsConfigKey.SYNC_AUTO.getKey(), true)).thenReturn(true);
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_AUTO_SCOPE.getKey()), anyString()))
                .thenReturn(WmsConstants.SYNC_SCOPE_TODAY);
        when(reconcileService.createRun(any(), anyString())).thenReturn("run-1");
    }

    private WmsSyncReconcileJob job() {
        return new WmsSyncReconcileJob(systemConfigService, reconcileService, syncRunMapper);
    }

    private WmsSyncRunCreateDTO captureDto() {
        ArgumentCaptor<WmsSyncRunCreateDTO> captor = ArgumentCaptor.forClass(WmsSyncRunCreateDTO.class);
        verify(reconcileService).createRun(captor.capture(), eq(WmsConstants.AUDIT_OPERATOR_SYSTEM));
        return captor.getValue();
    }

    /** sync.auto=false：整轮跳过，不能碰对账服务，也不能白查水位线。 */
    @Test
    void scan_gateOff_skipsCompletely() {
        when(systemConfigService.getBool(WmsConfigKey.SYNC_AUTO.getKey(), true)).thenReturn(false);

        job().scanForTest();

        verifyNoInteractions(reconcileService);
        verifyNoInteractions(syncRunMapper);
    }

    /** WAREHOUSE 需要仓库列表，定时任务没有上下文——跳过并报错，绝不能建一个缺仓库的批次。 */
    @Test
    void scan_warehouseScope_skips() {
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_AUTO_SCOPE.getKey()), anyString()))
                .thenReturn(WmsConstants.SYNC_SCOPE_WAREHOUSE);

        job().scanForTest();

        verifyNoInteractions(reconcileService);
        verifyNoInteractions(syncRunMapper);
    }

    /** 非法/未知范围同样跳过，避免 createRun 抛异常后天天留一条报错。 */
    @Test
    void scan_unknownScope_skips() {
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_AUTO_SCOPE.getKey()), anyString()))
                .thenReturn("MONTHLY");

        job().scanForTest();

        verifyNoInteractions(reconcileService);
    }

    /** TODAY：水位线取「最近成功批次的 window_end」。 */
    @Test
    void scan_today_usesLatestSuccessWindowEndAsWatermark() {
        Date watermark = new Date(1762268400000L);
        when(syncRunMapper.selectOne(any(Wrapper.class)))
                .thenReturn(new WmsSyncRun()
                        .setStatus(WmsConstants.SYNC_RUN_STATUS_SUCCESS)
                        .setWindowStart(new Date(1762182000000L))
                        .setWindowEnd(watermark));

        job().scanForTest();

        WmsSyncRunCreateDTO dto = captureDto();
        assertEquals(WmsConstants.SYNC_SCOPE_TODAY, dto.getScopeType());
        assertEquals(watermark, dto.getWindowStart());

        // 只断言返回值不够：漏写 status/scope/operator/排序时用例仍会通过
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WmsSyncRun>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(syncRunMapper).selectOne(wrapperCaptor.capture());
        Wrapper<WmsSyncRun> wrapper = wrapperCaptor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status"), "水位线未按批次状态过滤：" + sql);
        assertTrue(sql.contains("scope_type"), "水位线未限定 FULL/TODAY，WAREHOUSE 会污染水位线：" + sql);
        assertTrue(sql.contains("operator"), "水位线未限定 operator=system，手工批次会推高水位线：" + sql);
        assertTrue(sql.contains("ORDER BY window_end DESC"), "水位线未按 window_end 倒序取最新：" + sql);

        // 列名通过不代表取值正确（缓办项）：逐个校验绑定值
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        assertTrue(params.containsValue(WmsConstants.SYNC_RUN_STATUS_SUCCESS),
                "水位线未限定成功批次：" + params);
        assertTrue(params.containsValue(WmsConstants.SYNC_SCOPE_FULL),
                "水位线未限定 FULL：" + params);
        assertTrue(params.containsValue(WmsConstants.SYNC_SCOPE_TODAY),
                "水位线未限定 TODAY：" + params);
        assertTrue(params.containsValue(WmsConstants.AUDIT_OPERATOR_SYSTEM),
                "水位线未限定定时批次（operator=system）：" + params);
    }

    /** TODAY 且无历史成功批次：回落当天 00:00。 */
    @Test
    void scan_todayWithoutHistory_fallsBackToStartOfToday() {
        when(syncRunMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        job().scanForTest();

        WmsSyncRunCreateDTO dto = captureDto();
        long expected = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, dto.getWindowStart().getTime());
    }

    /** FULL 永远是全量快照：windowStart 必须为 null（传了会被 createRun 拒绝），且不查水位线。 */
    @Test
    void scan_fullScope_passesNullWindowStart() {
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_AUTO_SCOPE.getKey()), anyString()))
                .thenReturn(WmsConstants.SYNC_SCOPE_FULL);
        when(syncRunMapper.selectOne(any(Wrapper.class)))
                .thenReturn(new WmsSyncRun().setWindowEnd(new Date()));

        job().scanForTest();

        WmsSyncRunCreateDTO dto = captureDto();
        assertEquals(WmsConstants.SYNC_SCOPE_FULL, dto.getScopeType());
        assertNull(dto.getWindowStart());
        verify(syncRunMapper, never()).selectOne(any(Wrapper.class));
    }

    /** createRun 抛异常不能把 Quartz 任务带崩（否则下次调度可能被 Blocked 掉）。 */
    @Test
    void scan_createRunThrows_swallowed() {
        when(reconcileService.createRun(any(), anyString())).thenThrow(new RuntimeException("已有批次执行中"));

        assertDoesNotThrow(() -> job().scanForTest());
    }

    /** 任务必须真的挂到 Quartz，否则逻辑再对也永不执行。 */
    @Test
    void registrar_schedulesQuartzJob() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(scheduler.getTriggersOfJob(any(JobKey.class))).thenReturn(List.of());

        new WmsSyncReconcileJobRegistrar(scheduler).register();

        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }
}
