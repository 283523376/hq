package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.mapper.WmsBatchTraceMapper;
import org.jeecg.modules.wms.vo.WmsBatchFlowVO;
import org.jeecg.modules.wms.vo.WmsInventoryStockRowVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次追溯：入参校验与条数上限。
 */
class WmsBatchTraceServiceImplTest {

    private WmsBatchTraceMapper mapper;
    private WmsBatchTraceServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsBatchTraceMapper.class);
        service = new WmsBatchTraceServiceImpl(mapper);
    }

    @Test
    void rejectsBlankBatchNo() {
        assertTrue(assertThrows(RuntimeException.class, () -> service.batchStock(" "))
                .getMessage().contains("批次号不能为空"));
        assertTrue(assertThrows(RuntimeException.class, () -> service.batchFlow(null, 10))
                .getMessage().contains("批次号不能为空"));
    }

    @Test
    void trimsBatchNoBeforeQuery() {
        when(mapper.selectBatchStock("B1")).thenReturn(List.of(new WmsInventoryStockRowVO()));
        assertEquals(1, service.batchStock("  B1  ").size());
    }

    @Test
    void appliesDefaultAndMaxFlowLimit() {
        when(mapper.selectBatchFlow(anyString(), anyInt())).thenReturn(List.<WmsBatchFlowVO>of());

        service.batchFlow("B1", 0);
        verify(mapper).selectBatchFlow("B1", 200);

        service.batchFlow("B1", 99_999);
        verify(mapper).selectBatchFlow("B1", 500);
    }

    @Test
    void passesThroughReasonableLimit() {
        when(mapper.selectBatchFlow(eq("B1"), eq(50))).thenReturn(List.<WmsBatchFlowVO>of());
        service.batchFlow("B1", 50);
        verify(mapper).selectBatchFlow("B1", 50);
    }
}
