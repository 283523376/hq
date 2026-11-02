package org.jeecg.modules.wms.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.wms.constant.WmsConfigKey;
import org.jeecg.modules.wms.entity.WmsAuditLog;
import org.jeecg.modules.wms.mapper.WmsAuditLogMapper;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobKey;
import org.quartz.Scheduler;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Description: 审计日志保留期清理（XT-002）。
 *
 * <p>清理是本模块唯一会删数据的路径，两种错法都很致命：
 * 保留期配成 0 却照删（一次清空全部审计），或该删的不删（表无限膨胀）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditCleanJobTest {

    private WmsAuditLogMapper auditLogMapper;
    private IWmsSystemConfigService systemConfigService;

    /** LambdaQueryWrapper 取 SQL 片段前要先注册实体元数据（纯单测没有 MyBatis 启动过程）。 */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                WmsAuditLog.class);
    }

    @BeforeEach
    void setUp() {
        auditLogMapper = mock(WmsAuditLogMapper.class);
        systemConfigService = mock(IWmsSystemConfigService.class);
    }

    private WmsAuditCleanJob job() {
        return new WmsAuditCleanJob(auditLogMapper, systemConfigService);
    }

    private LambdaQueryWrapper<WmsAuditLog> captureDeleteWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<WmsAuditLog>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(auditLogMapper, org.mockito.Mockito.atLeastOnce()).delete(captor.capture());
        return captor.getValue();
    }

    /** 配 30 天时，删除线应当落在「30 天前」——31 天前的记录被删，29 天前的保留。 */
    @Test
    void clean_deletesRecordsOlderThanConfiguredDays() {
        when(systemConfigService.getString(WmsConfigKey.AUDIT_RETENTION_DAYS.getKey(), "365"))
                .thenReturn("30");
        when(auditLogMapper.delete(any())).thenReturn(0);

        long before = System.currentTimeMillis();
        job().clean();
        long after = System.currentTimeMillis();

        LambdaQueryWrapper<WmsAuditLog> wrapper = captureDeleteWrapper();
        assertTrue(wrapper.getSqlSegment().contains("create_time"), "实际：" + wrapper.getSqlSegment());
        Object cutoff = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof Date).findFirst().orElseThrow();
        long expectedMin = before - 30L * 24 * 3600 * 1000;
        long expectedMax = after - 30L * 24 * 3600 * 1000;
        long actual = ((Date) cutoff).getTime();
        assertTrue(actual >= expectedMin - 1000 && actual <= expectedMax + 1000,
                "删除线不是 30 天前：" + new Date(actual));
    }

    /** 0 或负数一律跳过并报错：宁可日志表涨，也不能一次清空全部审计。 */
    @Test
    void clean_invalidRetention_skipsInsteadOfWiping() {
        when(systemConfigService.getString(anyString(), anyString())).thenReturn("0");

        int deleted = job().clean();

        assertEquals(0, deleted);
        verifyNoInteractions(auditLogMapper);
    }

    /** 非数字同样跳过——「三十天」这种输入不能被当成 0 天。 */
    @Test
    void clean_nonNumericRetention_skips() {
        when(systemConfigService.getString(anyString(), anyString())).thenReturn("三十天");

        assertEquals(0, job().clean());
        verifyNoInteractions(auditLogMapper);
    }

    /** 分批删：每批删满就继续，直到删不满为止。 */
    @Test
    void clean_deletesInBatches() {
        when(systemConfigService.getString(anyString(), anyString())).thenReturn("30");
        when(auditLogMapper.delete(any())).thenReturn(WmsAuditCleanJob.BATCH_SIZE, 0);

        int deleted = job().clean();

        assertEquals(WmsAuditCleanJob.BATCH_SIZE, deleted);
        verify(auditLogMapper, org.mockito.Mockito.times(2)).delete(any());
    }

    /** 清理任务必须真的挂到 Quartz 上，否则再正确也永不执行。 */
    @Test
    void clean_registeredAsQuartzJob() throws Exception {
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.checkExists(any(JobKey.class))).thenReturn(false);
        when(scheduler.getTriggersOfJob(any(JobKey.class))).thenReturn(java.util.List.of());

        new WmsAuditCleanJobRegistrar(scheduler).register();

        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }
}
