package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.service.IWmsMaterialCategoryService;
import org.jeecg.modules.wms.service.IWmsMaterialGroupMapService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeeAuthService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private IWmsMaterialGroupMapService groupMapService;
    private IWmsKingdeeAuthService authService;
    private WmsKingdeeCategorySyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        categoryService = mock(IWmsMaterialCategoryService.class);
        categoryMapper = mock(WmsMaterialCategoryMapper.class);
        IWmsSyncLogService syncLogService = mock(IWmsSyncLogService.class);
        groupMapService = mock(IWmsMaterialGroupMapService.class);
        authService = mock(IWmsKingdeeAuthService.class);
        when(authService.isConfigured()).thenReturn(true);
        // 映射表为空是合法初始态：分类类型全部留 NULL，同步照常进行
        when(groupMapService.typeByGroupNumber()).thenReturn(new LinkedHashMap<>());

        service = new WmsKingdeeCategorySyncServiceImpl(
                formClient, properties, categoryService, categoryMapper, syncLogService);
        WmsTestSupport.inject(service, "materialGroupMapService", groupMapService);
        WmsTestSupport.inject(service, "kingdeeAuthService", authService);
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

    // ------------------------------------------------------------ 按物料分组建行（2026-11-05）

    /** 拉取物料分组 → 每个分组建一行分类；映射表命中时写分类类型，未命中留 NULL。 */
    @Test
    void syncMaterialGroups_shouldCreateOneCategoryPerGroupWithMappedType() {
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("XA.00.01010101", WmsConstants.CATEGORY_TYPE_RAW));
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制"),
                        List.of("A.C1.1.Y75", "Y75材质"),
                        // 同一分组的第二行不能重复建分类
                        List.of("XA.00.01010101", "自制")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(3, vo.getFetched(), "fetched 是扫描的物料行数");
        assertEquals(2, vo.getCreated(), "2996 个分组按去重后的分组数建行");
        ArgumentCaptor<WmsMaterialCategory> captor = ArgumentCaptor.forClass(WmsMaterialCategory.class);
        verify(categoryMapper, times(2)).insert(captor.capture());
        WmsMaterialCategory mapped = captor.getAllValues().get(0);
        assertEquals("XA.00.01010101", mapped.getCategoryCode());
        assertEquals("XA.00.01010101", mapped.getErpGroupNumber());
        assertEquals("自制", mapped.getCategoryName());
        assertEquals(WmsConstants.CATEGORY_TYPE_RAW, mapped.getCategoryType());
        assertEquals(WmsConstants.ROOT_PID_VALUE, mapped.getParentId(), "ERP 分组是平铺的，暂不建树");
        assertEquals(WmsConstants.STATUS_ENABLE, mapped.getStatus());

        WmsMaterialCategory unmapped = captor.getAllValues().get(1);
        assertEquals("A.C1.1.Y75", unmapped.getCategoryCode());
        assertNull(unmapped.getCategoryType(), "映射不到时不能猜成「原材料」");
    }

    /**
     * 映射表为空（合法初始态）：全部分类类型留 NULL，不报错、不猜「原材料」。
     */
    @Test
    void syncMaterialGroups_shouldLeaveTypeNullWhenMappingTableEmpty() {
        when(groupMapService.typeByGroupNumber()).thenReturn(new LinkedHashMap<>());
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制"),
                        List.of("A.C1.1.Y75", "Y75材质")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(2, vo.getCreated());
        ArgumentCaptor<WmsMaterialCategory> captor = ArgumentCaptor.forClass(WmsMaterialCategory.class);
        verify(categoryMapper, times(2)).insert(captor.capture());
        for (WmsMaterialCategory created : captor.getAllValues()) {
            assertNull(created.getCategoryType(), "映射表为空时不能猜分类类型");
        }
    }

    /** 幂等：已存在且名称未变时不重复插入、也不产生更新。 */
    @Test
    void syncMaterialGroups_shouldBeIdempotent() {
        WmsMaterialCategory existing = new WmsMaterialCategory();
        existing.setId("CAT1");
        existing.setCategoryCode("XA.00.01010101");
        existing.setCategoryName("自制");
        existing.setErpGroupNumber("XA.00.01010101");
        existing.setErpGroupName("自制");
        when(categoryMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(0, vo.getCreated());
        assertEquals(0, vo.getRenamed());
        verify(categoryMapper, never()).insert(any(WmsMaterialCategory.class));
        verify(categoryMapper, never()).updateById(any(WmsMaterialCategory.class));
    }

    /** 已存在但名称变化：只更新名称，不重复插入。 */
    @Test
    void syncMaterialGroups_shouldUpdateNameWhenChanged() {
        WmsMaterialCategory existing = new WmsMaterialCategory();
        existing.setId("CAT1");
        existing.setCategoryCode("XA.00.01010101");
        existing.setCategoryName("旧名称");
        existing.setErpGroupNumber("XA.00.01010101");
        existing.setErpGroupName("旧名称");
        when(categoryMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getRenamed());
        verify(categoryMapper).updateById(existing);
        assertEquals("自制", existing.getCategoryName());
    }

    /** 同步开关关闭必须显式失败，不能返回「同步完成，0 个」。 */
    @Test
    void syncMaterialGroups_shouldFailExplicitlyWhenDisabled() {
        properties.setEnabled(false);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.syncMaterialGroups());

        assertTrue(error.getMessage().contains("未开启"), error.getMessage());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    /** 金蝶未配置凭据同样显式失败。 */
    @Test
    void syncMaterialGroups_shouldFailExplicitlyWhenKingdeeNotConfigured() {
        when(authService.isConfigured()).thenReturn(false);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.syncMaterialGroups());

        assertTrue(error.getMessage().contains("未配置"), error.getMessage());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    private static List<List<Object>> rows(List<Object>... items) {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> item : items) {
            rows.add(new ArrayList<>(item));
        }
        return rows;
    }
}
