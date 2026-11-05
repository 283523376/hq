package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsMaterialGroupMap;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialGroupMapMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsMaterialGroupMapSaveResultVO;
import org.jeecg.modules.wms.vo.WmsUnmappedGroupVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 物料分组映射表单测——批量 upsert 幂等、非法类型整批拒绝、
 * 表为空不误判、未映射分组只返回未映射项。
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
class WmsMaterialGroupMapServiceImplTest {

    private WmsMaterialGroupMapMapper baseMapper;
    private WmsMaterialCategoryMapper categoryMapper;
    private WmsMaterialMapper materialMapper;
    private WmsMaterialGroupMapServiceImpl service;

    @BeforeEach
    void setUp() {
        baseMapper = mock(WmsMaterialGroupMapMapper.class);
        categoryMapper = mock(WmsMaterialCategoryMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        service = new WmsMaterialGroupMapServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", baseMapper);
        WmsTestSupport.inject(service, "categoryMapper", categoryMapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);
    }

    // ------------------------------------------------------------ 批量 upsert

    /** 新增：按分组编码插入，deleted_at 显式为 0（唯一索引才能与历史删除行共存）。 */
    @Test
    void saveBatchUpsert_shouldInsertNewMappings() {
        when(baseMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(baseMapper.insert(any(WmsMaterialGroupMap.class))).thenReturn(1);

        WmsMaterialGroupMapSaveResultVO vo = service.saveBatchUpsert(List.of(
                map(" XA.00.01010101 ", WmsConstants.CATEGORY_TYPE_RAW, "自制"),
                map("A.C1.1.Y75", WmsConstants.CATEGORY_TYPE_HALF_FINISHED, null)));

        assertEquals(2, vo.getCreated());
        assertEquals(0, vo.getUpdated());
        assertEquals(0, vo.getUnchanged());
        ArgumentCaptor<WmsMaterialGroupMap> captor = ArgumentCaptor.forClass(WmsMaterialGroupMap.class);
        verify(baseMapper, times(2)).insert(captor.capture());
        WmsMaterialGroupMap first = captor.getAllValues().get(0);
        assertEquals("XA.00.01010101", first.getGroupNumber(), "分组编码应 trim");
        assertEquals(WmsConstants.CATEGORY_TYPE_RAW, first.getCategoryType());
        assertEquals(0L, first.getDeletedAt());
        assertEquals(WmsConstants.CATEGORY_TYPE_HALF_FINISHED, captor.getAllValues().get(1).getCategoryType());
    }

    /** 幂等：已存在且内容一致时不插入、不更新（避免无意义的 update_time 抖动）。 */
    @Test
    void saveBatchUpsert_shouldBeIdempotent() {
        WmsMaterialGroupMap existing = existing("G1", WmsConstants.CATEGORY_TYPE_RAW, null);
        when(baseMapper.selectList(any())).thenReturn(List.of(existing));

        WmsMaterialGroupMapSaveResultVO vo = service.saveBatchUpsert(
                List.of(map("G1", WmsConstants.CATEGORY_TYPE_RAW, null)));

        assertEquals(0, vo.getCreated());
        assertEquals(0, vo.getUpdated());
        assertEquals(1, vo.getUnchanged());
        verify(baseMapper, never()).insert(any(WmsMaterialGroupMap.class));
        verify(baseMapper, never()).updateById(any(WmsMaterialGroupMap.class));
    }

    /** 已存在：分类类型变化时更新，且 update 审计字段被刷新。 */
    @Test
    void saveBatchUpsert_shouldUpdateExistingMapping() {
        WmsMaterialGroupMap existing = existing("G1", WmsConstants.CATEGORY_TYPE_RAW, "旧备注");
        when(baseMapper.selectList(any())).thenReturn(List.of(existing));
        when(baseMapper.updateById(any(WmsMaterialGroupMap.class))).thenReturn(1);

        WmsMaterialGroupMapSaveResultVO vo = service.saveBatchUpsert(
                List.of(map("G1", WmsConstants.CATEGORY_TYPE_PRODUCT, "新备注")));

        assertEquals(1, vo.getUpdated());
        assertEquals(WmsConstants.CATEGORY_TYPE_PRODUCT, existing.getCategoryType());
        assertEquals("新备注", existing.getRemark());
        verify(baseMapper).updateById(existing);
    }

    /** 备注是业务字段：入参为 null 表示清空（支持用户主动删备注）。 */
    @Test
    void saveBatchUpsert_shouldClearRemarkWhenOmitted() {
        WmsMaterialGroupMap existing = existing("G1", WmsConstants.CATEGORY_TYPE_RAW, "旧备注");
        when(baseMapper.selectList(any())).thenReturn(List.of(existing));

        WmsMaterialGroupMapSaveResultVO vo = service.saveBatchUpsert(
                List.of(map("G1", WmsConstants.CATEGORY_TYPE_RAW, null)));

        assertEquals(1, vo.getUpdated());
        assertNull(existing.getRemark());
    }

    /** 归类类型非法：整批拒绝，绝不部分落库，文案列出允许值。 */
    @Test
    void saveBatchUpsert_shouldRejectWholeBatchOnInvalidType() {
        when(baseMapper.selectList(any())).thenReturn(new ArrayList<>());

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.saveBatchUpsert(List.of(
                map("G1", WmsConstants.CATEGORY_TYPE_RAW, null),
                map("G2", "产成品", null))));

        assertTrue(error.getMessage().contains("分类类型非法"), error.getMessage());
        assertTrue(error.getMessage().contains("原材料/半成品/成品/辅料"), error.getMessage());
        assertTrue(error.getMessage().contains("第 2 行"), error.getMessage());
        verify(baseMapper, never()).insert(any(WmsMaterialGroupMap.class));
        verify(baseMapper, never()).updateById(any(WmsMaterialGroupMap.class));
    }

    /** 空入参：明确报错而不是静默成功。 */
    @Test
    void saveBatchUpsert_shouldRejectEmptyInput() {
        assertThrows(RuntimeException.class, () -> service.saveBatchUpsert(null));
        assertThrows(RuntimeException.class, () -> service.saveBatchUpsert(List.of()));
        verify(baseMapper, never()).insert(any(WmsMaterialGroupMap.class));
    }

    /** 同一批里重复分组编码：后者覆盖前者（与旧配置串解析语义一致），只落一行。 */
    @Test
    void saveBatchUpsert_shouldDeduplicateSameGroupWithinBatch() {
        when(baseMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(baseMapper.insert(any(WmsMaterialGroupMap.class))).thenReturn(1);

        WmsMaterialGroupMapSaveResultVO vo = service.saveBatchUpsert(List.of(
                map("G1", WmsConstants.CATEGORY_TYPE_RAW, null),
                map("G1", WmsConstants.CATEGORY_TYPE_PRODUCT, null)));

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsMaterialGroupMap> captor = ArgumentCaptor.forClass(WmsMaterialGroupMap.class);
        verify(baseMapper, times(1)).insert(captor.capture());
        assertEquals(WmsConstants.CATEGORY_TYPE_PRODUCT, captor.getValue().getCategoryType());
    }

    // ------------------------------------------------------------ 同步取映射

    /** 空表是合法初始态：返回空 Map，不报错（分类类型全部留 NULL）。 */
    @Test
    void typeByGroupNumber_shouldReturnEmptyMapWhenTableEmpty() {
        when(baseMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertTrue(service.typeByGroupNumber().isEmpty());
    }

    /** 只把有类型的行放进映射；空类型行不参与（同步时按「映射不到」处理）。 */
    @Test
    void typeByGroupNumber_shouldIgnoreBlankType() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
                existing("G1", WmsConstants.CATEGORY_TYPE_RAW, null),
                existing("G2", null, null)));

        Map<String, String> mapping = service.typeByGroupNumber();

        assertEquals(1, mapping.size());
        assertEquals(WmsConstants.CATEGORY_TYPE_RAW, mapping.get("G1"));
    }

    // ------------------------------------------------------------ 未映射分组

    /** 只返回 category_type 为 NULL 的分组，并带上物料数、按物料数降序。 */
    @Test
    void listUnmappedGroups_shouldReturnOnlyUnmappedWithMaterialCount() {
        WmsMaterialCategory unmappedBig = category("C1", "G1", "大分组", null);
        WmsMaterialCategory mapped = category("C2", "G2", "已映射分组", WmsConstants.CATEGORY_TYPE_RAW);
        WmsMaterialCategory unmappedSmall = category("C3", "G3", "小分组", "");
        when(categoryMapper.selectList(any()))
                .thenReturn(List.of(unmappedBig, mapped, unmappedSmall));
        List<Map<String, Object>> counts = new ArrayList<>();
        counts.add(count("C1", 100L));
        counts.add(count("C3", 2L));
        when(materialMapper.selectMaps(any())).thenReturn(counts);

        List<WmsUnmappedGroupVO> result = service.listUnmappedGroups();

        assertEquals(2, result.size(), "已映射分组必须被排除");
        assertEquals("G1", result.get(0).getGroupNumber());
        assertEquals(100L, result.get(0).getMaterialCount());
        assertEquals("G3", result.get(1).getGroupNumber());
        assertEquals(2L, result.get(1).getMaterialCount());
        verify(materialMapper, times(1)).selectMaps(any());
    }

    /** 没有已同步分类行时返回空列表，不查物料表。 */
    @Test
    void listUnmappedGroups_shouldReturnEmptyWhenNoCategories() {
        when(categoryMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertTrue(service.listUnmappedGroups().isEmpty());
        verify(materialMapper, never()).selectMaps(any());
    }

    /** 全部已映射时返回空列表，同样不查物料表。 */
    @Test
    void listUnmappedGroups_shouldReturnEmptyWhenAllMapped() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category("C2", "G2", "已映射分组", WmsConstants.CATEGORY_TYPE_RAW)));

        assertTrue(service.listUnmappedGroups().isEmpty());
        verify(materialMapper, never()).selectMaps(any());
    }

    // ------------------------------------------------------------ helpers

    private static WmsMaterialGroupMap map(String groupNumber, String type, String remark) {
        return new WmsMaterialGroupMap()
                .setGroupNumber(groupNumber)
                .setCategoryType(type)
                .setRemark(remark);
    }

    private static WmsMaterialGroupMap existing(String groupNumber, String type, String remark) {
        // setId 来自 JeecgEntity：链式调用到父类后返回父类型，故拆成独立语句
        WmsMaterialGroupMap map = new WmsMaterialGroupMap();
        map.setId("ID-" + groupNumber);
        map.setGroupNumber(groupNumber);
        map.setGroupName("名称-" + groupNumber);
        map.setCategoryType(type);
        map.setRemark(remark);
        map.setDeletedAt(0L);
        return map;
    }

    private static WmsMaterialCategory category(String id, String groupNumber,
                                                String name, String categoryType) {
        // setId 来自 JeecgEntity，链式调用后返回父类型，故拆成独立语句
        WmsMaterialCategory category = new WmsMaterialCategory();
        category.setId(id);
        category.setCategoryCode(groupNumber);
        category.setCategoryName(name);
        category.setErpGroupNumber(groupNumber);
        category.setErpGroupName(name);
        category.setCategoryType(categoryType);
        return category;
    }

    private static Map<String, Object> count(String categoryId, long materialCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("category_id", categoryId);
        row.put("material_count", materialCount);
        return row;
    }
}
