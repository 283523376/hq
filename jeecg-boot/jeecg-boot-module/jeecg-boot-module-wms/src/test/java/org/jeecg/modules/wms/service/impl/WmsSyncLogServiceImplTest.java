package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsSyncLog;
import org.jeecg.modules.wms.mapper.WmsSyncLogMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 同步日志服务单测（脱敏/截断/落库/失败单重试查询）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsSyncLogServiceImplTest {

    private WmsSyncLogMapper mapper;
    private WmsSyncLogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsSyncLogMapper.class);
        service = new WmsSyncLogServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mapper);
    }

    @Test
    void sanitizeMasksTokensAndTruncates() {
        StringBuilder big = new StringBuilder();
        big.append("POST body X-MES-Token: abc123456 appSecret ok \"appSecret\":\"super-secret\" ")
           .append("\"access_token\":\"json-tok\" Authorization: Bearer bearer-tok Cookie: kdservice-sessionid=cookie-tok\n")
           .append("https://x?access_token=tok123&next=1 ");
        while (big.length() < 9000) {
            big.append('填');
        }
        String out = WmsSyncLogServiceImpl.sanitize(big.toString());
        assertFalse(out.contains("abc123456"));
        assertFalse(out.contains("super-secret"));
        assertFalse(out.contains("tok123"));
        assertFalse(out.contains("json-tok"));
        assertFalse(out.contains("bearer-tok"));
        assertFalse(out.contains("cookie-tok"));
        assertTrue(out.contains("***"));
        assertTrue(out.endsWith("[截断]"));
        assertTrue(out.length() <= 8192 + 8);
    }

    @Test
    void logSyncWritesSuccessAndFailureRows() {
        service.logSync("ERP_TO_WMS", "WORK_ORDER", "WO1", "KINGDEE", true, 0,
                "{\"a\":1}", "{\"ok\":true}", null, 120);
        ArgumentCaptor<WmsSyncLog> cap = ArgumentCaptor.forClass(WmsSyncLog.class);
        verify(mapper).insert(cap.capture());
        WmsSyncLog row = cap.getValue();
        assertEquals("ERP_TO_WMS", row.getDirection());
        assertEquals("WORK_ORDER", row.getObjectType());
        assertEquals("WO1", row.getObjectNo());
        assertEquals("同步成功", row.getStatus());
        assertEquals(120L, row.getCostMs().longValue());

        service.logSync("ERP_TO_WMS", "WORK_ORDER", "WO2", "KINGDEE", false, 2,
                null, null, "字段缺失", 30);
        ArgumentCaptor<WmsSyncLog> cap2 = ArgumentCaptor.forClass(WmsSyncLog.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(cap2.capture());
        WmsSyncLog fail = cap2.getAllValues().get(1);
        assertEquals("同步失败", fail.getStatus());
        assertEquals("字段缺失", fail.getErrorMessage());
        assertEquals(1, fail.getRetryCount());
    }

    @Test
    void logSyncCountsConsecutiveFailures() {
        when(mapper.selectCount(any())).thenReturn(2L);
        service.logSync("ERP_TO_WMS", "WORK_ORDER", "WO1", "KINGDEE", false, 1,
                null, null, "boom", 10);
        ArgumentCaptor<WmsSyncLog> cap = ArgumentCaptor.forClass(WmsSyncLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals(3, cap.getValue().getRetryCount());
    }

    @Test
    void logSyncTruncatesLongError() {
        String longError = "x".repeat(600);
        service.logSync("ERP_TO_WMS", "WORK_ORDER", "WO1", "KINGDEE", false, 1,
                null, null, longError, 10);
        ArgumentCaptor<WmsSyncLog> cap = ArgumentCaptor.forClass(WmsSyncLog.class);
        verify(mapper).insert(cap.capture());
        assertEquals(500, cap.getValue().getErrorMessage().length());
    }

    @Test
    void listFailedObjectNosDedups() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row("WO1", "同步失败", 1), row("WO1", "同步失败", 2), row("WO2", "同步失败", 1)));
        List<String> nos = service.listFailedObjectNos("WORK_ORDER", 3, 7);
        assertEquals(2, nos.size());
        assertTrue(nos.contains("WO1"));
        assertTrue(nos.contains("WO2"));
    }

    @Test
    void listFailedObjectNosExcludesResolvedAndExhausted() {
        // 倒序：WO1 最新成功 → 已解决；WO2 最新 retry=3 → 达上限；WO3 retry=1 → 待重试
        when(mapper.selectList(any())).thenReturn(List.of(
                row("WO1", "同步成功", 0), row("WO1", "同步失败", 2),
                row("WO2", "同步失败", 3), row("WO3", "同步失败", 1)));
        List<String> nos = service.listFailedObjectNos("WORK_ORDER", 3, 7);
        assertEquals(2, nos.size());
        assertTrue(nos.contains("WO2"));
        assertTrue(nos.contains("WO3"));
        assertEquals(List.of("WO2"), service.listExhaustedObjectNos("WORK_ORDER", 3, 7));
    }

    private WmsSyncLog row(String no, String status, int retry) {
        WmsSyncLog log = new WmsSyncLog();
        log.setObjectNo(no);
        log.setStatus(status);
        log.setRetryCount(retry);
        return log;
    }
}
