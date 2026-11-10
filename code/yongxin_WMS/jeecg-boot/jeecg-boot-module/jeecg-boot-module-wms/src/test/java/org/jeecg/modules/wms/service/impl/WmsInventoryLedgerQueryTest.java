package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存台账查询参数归一化（spec 2026-10-29 §5.1）。
 */
class WmsInventoryLedgerQueryTest {

    private WmsInventoryMapper mapper;
    private WmsInventoryLedgerServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsInventoryMapper.class);
        service = new WmsInventoryLedgerServiceImpl(mapper);
    }

    @Test
    void pageSizeCapAt200() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageSize(5000);
        assertEquals(200, WmsInventoryLedgerServiceImpl.normalize(q).getPageSize());
    }

    @Test
    void pageNoAndPageSizeDefaultWhenNull() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageNo(null);
        q.setPageSize(null);
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertEquals(1, n.getPageNo());
        assertEquals(20, n.getPageSize());
    }

    @Test
    void pageNoBelowOneFallsBackToOne() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageNo(0);
        assertEquals(1, WmsInventoryLedgerServiceImpl.normalize(q).getPageNo());
    }

    @Test
    void onlyPositiveDefaultsTrue() {
        assertEquals(Boolean.TRUE,
                WmsInventoryLedgerServiceImpl.normalize(new WmsInventoryLedgerQueryDTO()).getOnlyPositive());
    }

    @Test
    void blankKeywordTrimmedToNull() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setMaterialKeyword("   ");
        q.setBatchNo("");
        q.setLocationCode("  XB  ");
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertNull(n.getMaterialKeyword());
        assertNull(n.getBatchNo());
        assertEquals("XB", n.getLocationCode());
    }

    @Test
    void queryPagePassesNormalizedParamsToMapper() {
        when(mapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setPageSize(9999);
        q.setPageNo(3);
        service.queryPage(q);
        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor = ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(mapper).selectLedgerPage(any(), captor.capture());
        assertEquals(200, captor.getValue().getPageSize());
        assertEquals(3, captor.getValue().getPageNo());
    }

    @Test
    void emptyResultReturnsZeroTotalNotNullList() {
        Page<WmsInventoryLedgerVO> empty = new Page<>(1, 20);
        empty.setRecords(Collections.emptyList());
        when(mapper.selectLedgerPage(any(), any())).thenReturn(empty);
        IPage<WmsInventoryLedgerVO> page = service.queryPage(new WmsInventoryLedgerQueryDTO());
        assertEquals(0L, page.getTotal());
        assertNotNull(page.getRecords());
        assertEquals(0, page.getRecords().size());
    }
}
