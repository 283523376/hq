package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 物料主数据同步单测：恒等映射 upsert（新建/更新/存量手工物料不受伤）。
 */
class WmsKingdeeMaterialSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private IWmsKingdeeAuthService authService;
    private WmsMaterialMapper materialMapper;
    private WmsMaterialCategoryMapper materialCategoryMapper;
    private IWmsSyncLogService syncLogService;
    private WmsKingdeeMaterialSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        authService = mock(IWmsKingdeeAuthService.class);
        when(authService.isConfigured()).thenReturn(true);
        materialMapper = mock(WmsMaterialMapper.class);
        materialCategoryMapper = mock(WmsMaterialCategoryMapper.class);
        when(materialCategoryMapper.selectList(any())).thenReturn(List.of());
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsKingdeeMaterialSyncServiceImpl(
                formClient, properties, authService, materialMapper, syncLogService);
        WmsTestSupport.inject(service, "materialCategoryMapper", materialCategoryMapper);
    }

    private void stubFetch(String... numbers) {
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
        for (String number : numbers) {
            // FMATERIALID,FNumber,FName,FSpecification,FBaseUnitId.FNumber,
            // FMaterialGroup.FNumber,FMaterialGroup.FName
            rows.add(List.of(1, number, "名称-" + number, "规格-" + number, "zhi", "", ""));
        }
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows, null, null));
    }

    /** 带物料分组的取数夹具。 */
    private void stubFetchWithGroup(String number, String groupNumber, String groupName) {
        java.util.List<java.util.List<Object>> rows = new java.util.ArrayList<>();
        rows.add(List.of(1, number, "名称-" + number, "规格-" + number, "zhi", groupNumber, groupName));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows, null, null));
    }

    @Test
    void sync_createsNewMaterialWithIdentityMapping() {
        stubFetch("KD.001");
        when(materialMapper.selectOne(any())).thenReturn(null);

        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsMaterial> captor = ArgumentCaptor.forClass(WmsMaterial.class);
        verify(materialMapper).insert(captor.capture());
        assertEquals("KD.001", captor.getValue().getMaterialCode());
        // 恒等映射：金蝶码=自身编码
        assertEquals("KD.001", captor.getValue().getKingdeeCode());
        assertEquals("名称-KD.001", captor.getValue().getMaterialName());
        assertEquals("zhi", captor.getValue().getUnit());
    }

    @Test
    void sync_updatesExistingMaterialNonBlankFields() {
        stubFetch("KD.001");
        WmsMaterial existing = new WmsMaterial();
        existing.setMaterialCode("KD.001");
        existing.setMaterialName("旧名称");
        existing.setKingdeeCode("KD.001");
        when(materialMapper.selectOne(any())).thenReturn(existing);

        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();

        assertEquals(1, vo.getRenamed());
        verify(materialMapper).updateById(existing);
        assertEquals("名称-KD.001", existing.getMaterialName());
        assertEquals("规格-KD.001", existing.getSpec());
    }

    @Test
    void sync_skipsRowsWithoutNumber() {
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", new java.util.ArrayList<>(java.util.Arrays.asList(java.util.Arrays.asList(1, "", "无编码", null, null))), null, null));
        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();
        assertEquals(1, vo.getFetched());
        verify(materialMapper, never()).insert(any(WmsMaterial.class));
    }

    @Test
    void sync_skipsWhenKingdeeDisabled() {
        properties.setEnabled(false);
        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();
        assertTrue(vo.getMessage().contains("跳过"));
        verify(materialMapper, never()).insert(any(WmsMaterial.class));
    }

    @Test
    void sync_skipsWhenAuthNotConfigured() {
        when(authService.isConfigured()).thenReturn(false);
        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();
        assertTrue(vo.getMessage().contains("跳过"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    // ------------------------------------------------------------ 物料分组 → 分类挂接（2026-11-05）

    /** 分组编码命中分类表 erp_group_number → 物料落 category_id。 */
    @Test
    void sync_shouldAttachCategoryIdWhenGroupMatches() {
        stubFetchWithGroup("KD.001", "XA.00.01010101", "自制");
        when(materialMapper.selectOne(any())).thenReturn(null);
        when(materialCategoryMapper.selectList(any())).thenReturn(List.of(category("CAT1", "XA.00.01010101")));

        service.syncMaterial();

        ArgumentCaptor<WmsMaterial> captor = ArgumentCaptor.forClass(WmsMaterial.class);
        verify(materialMapper).insert(captor.capture());
        assertEquals("CAT1", captor.getValue().getCategoryId());
    }

    /** 分组查不到分类 → category_id 留 NULL，且同步不报错。 */
    @Test
    void sync_shouldLeaveCategoryIdNullWhenGroupNotMapped() {
        stubFetchWithGroup("KD.001", "UNMAPPED.GROUP", "未映射分组");
        when(materialMapper.selectOne(any())).thenReturn(null);
        when(materialCategoryMapper.selectList(any())).thenReturn(List.of());

        WmsKingdeeStockSyncResultVO vo = service.syncMaterial();

        assertEquals(1, vo.getCreated(), "映射缺失不能阻断物料同步");
        ArgumentCaptor<WmsMaterial> captor = ArgumentCaptor.forClass(WmsMaterial.class);
        verify(materialMapper).insert(captor.capture());
        assertNull(captor.getValue().getCategoryId());
    }

    /** 存量物料在映射命中后也要补挂分类。 */
    @Test
    void sync_shouldBackfillCategoryIdOnExistingMaterial() {
        stubFetchWithGroup("KD.001", "XA.00.01010101", "自制");
        WmsMaterial existing = new WmsMaterial();
        existing.setMaterialCode("KD.001");
        existing.setMaterialName("名称-KD.001");
        existing.setKingdeeCode("KD.001");
        existing.setCategoryId(null);
        when(materialMapper.selectOne(any())).thenReturn(existing);
        when(materialCategoryMapper.selectList(any())).thenReturn(List.of(category("CAT1", "XA.00.01010101")));

        service.syncMaterial();

        assertEquals("CAT1", existing.getCategoryId());
        verify(materialMapper).updateById(existing);
    }

    private static WmsMaterialCategory category(String id, String erpGroupNumber) {
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId(id);
        category.setErpGroupNumber(erpGroupNumber);
        return category;
    }
}
