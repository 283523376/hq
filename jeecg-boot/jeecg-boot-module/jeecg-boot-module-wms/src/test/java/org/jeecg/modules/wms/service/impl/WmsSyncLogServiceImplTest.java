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
           .append("https://x?access_token=tok123&next=1 ");
        while (big.length() < 9000) {
            big.append('填');
        }
        String out = WmsSyncLogServiceImpl.sanitize(big.toString());
        assertFalse(out.contains("abc123456"));
        assertFalse(out.contains("super-secret"));
        assertFalse(out.contains("tok123"));
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
        assertEquals(2, fail.getRetryCount());
    }

    @Test
    void listFailedObjectNosDedups() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row("WO1"), row("WO1"), row("WO2")));
        List<String> nos = service.listFailedObjectNos("WORK_ORDER", 3, 7);
        assertEquals(2, nos.size());
        assertTrue(nos.contains("WO1"));
        assertTrue(nos.contains("WO2"));
    }

    private WmsSyncLog row(String no) {
        WmsSyncLog log = new WmsSyncLog();
        log.setObjectNo(no);
        return log;
    }
}
