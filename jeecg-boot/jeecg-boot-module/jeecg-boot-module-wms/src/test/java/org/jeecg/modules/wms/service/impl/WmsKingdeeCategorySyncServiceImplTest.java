package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 物料分组 → 分类映射同步单测——未配置空转、前缀命中盖代表分组、无关分组跳过（spec §5.2）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeeCategorySyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private IWmsMaterialCategoryService categoryService;
    private WmsMaterialCategoryMapper categoryMapper;
    private WmsKingdeeCategorySyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        categoryService = mock(IWmsMaterialCategoryService.class);
        categoryMapper = mock(WmsMaterialCategoryMapper.class);
        IWmsSyncLogService syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsKingdeeCategorySyncServiceImpl(
                formClient, properties, categoryService, categoryMapper, syncLogService);
    }

    @Test
    void shouldNotQueryNorWriteWhenCategoryMapBlank() {
        WmsKingdeeStockSyncResultVO vo = service.syncCategory();

        assertEquals(0, vo.getFetched());
        assertTrue(vo.getMessage().contains("未配置"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
        verify(categoryService, never()).editCategory(any());
    }

    @Test
    void shouldStampRepresentativeGroupOnMatchedCategory() {
        properties.getMasterData().getCategoryMap().add("WA.2205=HALF");
        WmsMaterialCategory half = new WmsMaterialCategory();
        half.setId("CATHALF");
        half.setCategoryCode("HALF");
        when(categoryMapper.selectOne(any())).thenReturn(half);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("M1", "钻针A", "WA.2205.1100", "刃径刃长"),
                        List.of("M2", "盒子B", "BC.1012.1110", "上下套件")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncCategory();

        assertEquals(1, vo.getRenamed());
        ArgumentCaptor<WmsMaterialCategory> captor = ArgumentCaptor.forClass(WmsMaterialCategory.class);
        verify(categoryService).editCategory(captor.capture());
        WmsMaterialCategory updated = captor.getValue();
        assertEquals("HALF", updated.getCategoryCode());
        assertEquals("WA.2205.1100", updated.getErpGroupNumber());
        assertEquals("刃径刃长", updated.getErpGroupName());
    }

    @Test
    void shouldSkipGroupWithoutMatchingRule() {
        properties.getMasterData().getCategoryMap().add("NOPE.9999=CP");
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("M1", "钻针A", "WA.2205.1100", "刃径刃长")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncCategory();

        assertEquals(0, vo.getRenamed());
        verify(categoryService, never()).editCategory(any());
    }

    private static List<List<Object>> rows(List<Object>... items) {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> item : items) {
            rows.add(new ArrayList<>(item));
        }
        return rows;
    }
}
