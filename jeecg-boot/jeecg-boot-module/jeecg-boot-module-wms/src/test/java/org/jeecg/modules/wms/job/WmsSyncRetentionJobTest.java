package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.entity.WmsSyncDiff;
import org.jeecg.modules.wms.entity.WmsSyncFlow;
import org.jeecg.modules.wms.entity.WmsSyncLog;
import org.jeecg.modules.wms.entity.WmsSyncRun;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.jeecg.modules.wms.mapper.WmsSyncDiffMapper;
import org.jeecg.modules.wms.mapper.WmsSyncFlowMapper;
import org.jeecg.modules.wms.mapper.WmsSyncLogMapper;
import org.jeecg.modules.wms.mapper.WmsSyncRunMapper;
import org.jeecg.modules.wms.mapper.WmsSyncSnapshotMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.quartz.JobKey;
import org.quartz.Scheduler;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Description: SB-001 保留期清理任务（spec §2.9.1）。
 *
 * <p>清理是本模块唯一物理删数据的路径。两条红线：
 * 配置写成 0 / 非数字时必须**一条都不删**；删批次前必须先删子表（snapshot/flow/diff）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncRetentionJobTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private WmsSyncSnapshotMapper snapshotMapper;
    private WmsSyncFlowMapper flowMapper;
    private WmsSyncDiffMapper diffMapper;
    private WmsSyncRunMapper runMapper;
    private WmsSyncLogMapper syncLogMapper;
    private IWmsSystemConfigService systemConfigService;

    /** LambdaQueryWrapper 取 SQL 片段前要先注册实体元数据（纯单测没有 MyBatis 启动过程）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WmsSyncSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, WmsSyncFlow.class);
        TableInfoHelper.initTableInfo(assistant, WmsSyncDiff.class);
        TableInfoHelper.initTableInfo(assistant, WmsSyncRun.class);
        TableInfoHelper.initTableInfo(assistant, WmsSyncLog.class);
    }

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(WmsSyncSnapshotMapper.class);
        flowMapper = mock(WmsSyncFlowMapper.class);
        diffMapper = mock(WmsSyncDiffMapper.class);
        runMapper = mock(WmsSyncRunMapper.class);
        syncLogMapper = mock(WmsSyncLogMapper.class);
        systemConfigService = mock(IWmsSystemConfigService.class);
        stubConfig("180", "365", "90");
        when(snapshotMapper.delete(any())).thenReturn(0);
        when(flowMapper.delete(any())).thenReturn(0);
        when(diffMapper.delete(any())).thenReturn(0);
        when(runMapper.delete(any())).thenReturn(0);
        when(syncLogMapper.delete(any())).thenReturn(0);
    }

    private void stubConfig(String snapshotDays, String diffDays, String logDays) {
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_RETENTION_DAYS.getKey()), anyString()))
                .thenReturn(snapshotDays);
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_RETENTION_DIFF_DAYS.getKey()), anyString()))
                .thenReturn(diffDays);
        when(systemConfigService.getString(eq(WmsConfigKey.SYNC_LOG_RETENTION_DAYS.getKey()), anyString()))
                .thenReturn(logDays);
    }

    private WmsSyncRetentionJob job() {
        return new WmsSyncRetentionJob(snapshotMapper, flowMapper, diffMapper, runMapper,
                syncLogMapper, systemConfigService);
    }

    /** 从 LambdaQueryWrapper 里取出 cutoff 参数（唯一一个 Date 参数）。 */
    private Date captureCutoff(Object wrapper) {
        @SuppressWarnings("unchecked")
        LambdaQueryWrapper<?> query = (LambdaQueryWrapper<?>) wrapper;
        // Lambda 列与参数值是惰性求值的：先触发生成 SQL 片段，paramNameValuePairs 才有值
        query.getSqlSegment();
        return (Date) query.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof Date)
                .findFirst()
                .orElseThrow(() -> new AssertionError("删除条件里没有时间参数：" + query.getSqlSegment()));
    }

    /**
     * 合法配置：快照/流水/批次用 180 天、差异用 365 天、同步日志用 90 天，
     * 四条子表 + 批次各收到自己的 cutoff。
     */
    @Test
    void scan_legalConfig_eachMapperGetsCorrectCutoff() {
        long before = System.currentTimeMillis();
        job().scanForTest();
        long after = System.currentTimeMillis();

        assertCutoff(captureSnapshotCutoff(), before - 180 * DAY_MS, after - 180 * DAY_MS, "snapshot");
        assertCutoff(captureFlowCutoff(), before - 180 * DAY_MS, after - 180 * DAY_MS, "flow");
        assertCutoff(captureDiffCutoff(), before - 365 * DAY_MS, after - 365 * DAY_MS, "diff");
        assertCutoff(captureRunCutoff(), before - 180 * DAY_MS, after - 180 * DAY_MS, "run");
        assertCutoff(captureLogCutoff(), before - 90 * DAY_MS, after - 90 * DAY_MS, "log");
    }

    /** 顺序必须是子先父后：snapshot/flow/diff 删完才能删 run。 */
    @Test
    void scan_deletesChildrenBeforeParent() {
        job().scanForTest();

        InOrder order = inOrder(snapshotMapper, flowMapper, diffMapper, runMapper);
        order.verify(snapshotMapper).delete(any());
        order.verify(flowMapper).delete(any());
        order.verify(diffMapper).delete(any());
        order.verify(runMapper).delete(any());
    }

    /** 保留天数 0：一条都不删（宁可表涨，也不能一次清空对账证据）。 */
    @Test
    void scan_zeroDays_deletesNothing() {
        stubConfig("0", "0", "0");

        job().scanForTest();

        verifyNoInteractions(snapshotMapper, flowMapper, diffMapper, runMapper, syncLogMapper);
    }

    /** 非数字配置同样一条不删——「一百八十天」不能被当成 0 天。 */
    @Test
    void scan_nonNumericDays_deletesNothing() {
        stubConfig("一百八十天", "abc", "90d");

        job().scanForTest();

        verifyNoInteractions(snapshotMapper, flowMapper, diffMapper, runMapper, syncLogMapper);
    }

    /** 负数是非法值，不是「保留期更短」。 */
    @Test
    void scan_negativeDays_deletesNothing() {
        stubConfig("-1", "-1", "-1");

        job().scanForTest();

        verifyNoInteractions(snapshotMapper, flowMapper, diffMapper, runMapper, syncLogMapper);
    }

    /** 单项配置非法只跳过该项，不能连累其他表。 */
    @Test
    void scan_invalidSnapshotDays_stillCleansDiffAndLog() {
        stubConfig("0", "365", "90");

        job().scanForTest();

        verifyNoInteractions(snapshotMapper, flowMapper, runMapper);
        verify(diffMapper).delete(any());
        verify(syncLogMapper).delete(any());
    }

    /** 分批删：每批删满继续，删不满即停。 */
    @Test
    void scan_deletesInBatches() {
        when(snapshotMapper.delete(any())).thenReturn(WmsSyncRetentionJob.BATCH_SIZE, 0);

        job().scanForTest();

        verify(snapshotMapper, times(2)).delete(any());
    }

    /** 任务必须真的挂到 Quartz，否则逻辑再对也永不执行。 */
    @Test
    void registrar_schedulesQuartzJob() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(scheduler.getTriggersOfJob(any(JobKey.class))).thenReturn(List.of());

        new WmsSyncRetentionJobRegistrar(scheduler).register();

        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    // ---------------------------------------------------------------- 捕获辅助

    private Date captureSnapshotCutoff() {
        ArgumentCaptor<LambdaQueryWrapper<WmsSyncSnapshot>> captor = wrapperCaptor();
        verify(snapshotMapper).delete(captor.capture());
        return captureCutoff(captor.getValue());
    }

    private Date captureFlowCutoff() {
        ArgumentCaptor<LambdaQueryWrapper<WmsSyncFlow>> captor = wrapperCaptor();
        verify(flowMapper).delete(captor.capture());
        return captureCutoff(captor.getValue());
    }

    private Date captureDiffCutoff() {
        ArgumentCaptor<LambdaQueryWrapper<WmsSyncDiff>> captor = wrapperCaptor();
        verify(diffMapper).delete(captor.capture());
        return captureCutoff(captor.getValue());
    }

    private Date captureRunCutoff() {
        ArgumentCaptor<LambdaQueryWrapper<WmsSyncRun>> captor = wrapperCaptor();
        verify(runMapper).delete(captor.capture());
        return captureCutoff(captor.getValue());
    }

    private Date captureLogCutoff() {
        ArgumentCaptor<LambdaQueryWrapper<WmsSyncLog>> captor = wrapperCaptor();
        verify(syncLogMapper).delete(captor.capture());
        return captureCutoff(captor.getValue());
    }

    @SuppressWarnings("unchecked")
    private <T> ArgumentCaptor<LambdaQueryWrapper<T>> wrapperCaptor() {
        return (ArgumentCaptor<LambdaQueryWrapper<T>>) (ArgumentCaptor<?>)
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    private void assertCutoff(Date cutoff, long min, long max, String label) {
        long actual = cutoff.getTime();
        assertTrue(actual >= min - 2000 && actual <= max + 2000,
                label + " 的 cutoff 不是配置的天数前：" + new Date(actual));
    }
}
