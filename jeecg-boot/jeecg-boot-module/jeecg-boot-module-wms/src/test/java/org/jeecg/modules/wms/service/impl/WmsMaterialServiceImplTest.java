package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsBatchDeleteCheckVO;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 物料主数据业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsMaterialServiceImplTest {

    /** 造被测服务，并注入分类 Mapper（回填与编码解析都走它，裸 new 会因字段为空 NPE）。 */
    private static WmsMaterialServiceImpl serviceWithCategoryMapper(WmsMaterialCategoryMapper mapper) {
        WmsMaterialServiceImpl service = spy(new WmsMaterialServiceImpl());
        WmsTestSupport.inject(service, "wmsMaterialCategoryMapper", mapper);
        return service;
    }

    // ------------------------------------------------------------ 新增（BD001-T09/T10/T11）

    /** 新增时状态落默认值「启用」，并写入审计字段（BD001-T09）。 */
    @Test
    void addMaterial_shouldApplyDefaultStatus() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓")
                .setSpec("M8x20").setUnit("个");
        service.addMaterial(material);

        assertEquals(WmsConstants.STATUS_ENABLE, material.getStatus());
        assertNotNull(material.getCreateTime());
        verify(service).save(material);
    }

    /** 已显式指定状态时不得被默认值覆盖。 */
    @Test
    void addMaterial_shouldKeepExplicitStatus() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓")
                .setStatus(WmsConstants.STATUS_DISABLE);
        service.addMaterial(material);

        assertEquals(WmsConstants.STATUS_DISABLE, material.getStatus());
    }

    /** 编码重复必须拒绝（BD001-T10）。 */
    @Test
    void addMaterial_shouldRejectDuplicatedCode() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(true).when(service).checkCodeExists(any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addMaterial(new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓")));

        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    /**
     * ⚠ 现状记录（BD001-T11 缺口 G-11）：新增物料**不校验**分类是否存在，照样入库。
     *
     * <p>本用例固化当前行为，用于将来补上「分类不存在」校验时立即变红提醒更新。
     * 代码里唯一与分类存在性相关的落点是导入路径的 {@code resolveCategoryIdByCode}（见下方用例）。
     */
    @Test
    void addMaterial_shouldNotValidateCategoryExistence() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓")
                .setCategoryId("不存在的分类ID");

        assertDoesNotThrow(() -> service.addMaterial(material));
        verify(service).save(material);
        assertEquals("不存在的分类ID", material.getCategoryId());
    }

    /**
     * ⚠ 现状记录（Review Focus #3 / 缺口 G-TRIM）：物料编码**既不 trim 也不判空**，
     * 纯空白编码（"   "）可原样入库。
     *
     * <p>对比：{@code WmsSupplierServiceImpl.addSupplier} 与 {@code WmsOrderServiceImpl.addOrder} 都是
     * 「先 trim 再判空」，BD-002 的 Review Focus #2 正是为修这类缺陷而设。BD-001 因零测试而漏网——
     * 后果是 {@code "   "} 与 {@code ""} 在唯一索引 {@code uk_wms_material_code} 下是两个不同值，
     * 与「编码唯一」的业务语义不一致。
     *
     * <p>本用例走**真实的** checkCodeExists（未在 spy 上打桩），只桩掉底层 count。
     */
    @Test
    void addMaterial_currentlyAcceptsBlankCode() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        WmsMaterial material = new WmsMaterial().setMaterialCode("   ").setMaterialName("空白编码物料");
        service.addMaterial(material);

        verify(service).save(material);
        assertEquals("   ", material.getMaterialCode(), "现状：空白编码未被 trim，原样入库");
    }

    // ------------------------------------------------------------ 编辑（BD001-T12）

    /** 编辑时排除自身后编码可用。 */
    @Test
    void editMaterial_shouldUpdateWhenCodeFree() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsMaterial input = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓（改名）")
                .setSpec("M10x30");
        input.setId("1");
        service.editMaterial(input);

        assertNotNull(input.getUpdateTime());
        verify(service).checkCodeExists("M001", "1");
        verify(service).updateById(input);
    }

    /** 编辑时编码撞上别的物料必须拒绝。 */
    @Test
    void editMaterial_shouldRejectDuplicatedCode() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(true).when(service).checkCodeExists(any(), any());

        WmsMaterial input = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺栓");
        input.setId("1");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.editMaterial(input));
        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).updateById(any());
    }

    // ------------------------------------------------------------ 停用

    /** 停用只需一条更新，且把状态置为 0。 */
    @Test
    void disableMaterial_shouldSetStatusDisabled() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(true).when(service).updateById(any());

        service.disableMaterial("1");

        ArgumentCaptor<WmsMaterial> captor = ArgumentCaptor.forClass(WmsMaterial.class);
        verify(service).updateById(captor.capture());
        assertEquals("1", captor.getValue().getId());
        assertEquals(WmsConstants.STATUS_DISABLE, captor.getValue().getStatus());
    }

    // ------------------------------------------------------------ 删除与预检（BD001-T13/T14，spec 2026-10-16 §3.1）

    /** 物料当前无子表引用，预检恒可删；未来库存/订单明细表接入时在此方法里追加 blocker。 */
    @Test
    void checkDelete_shouldBeDeletable() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));

        WmsDeleteCheckVO vo = service.checkDelete("M1");

        assertTrue(vo.isDeletable());
    }

    /** 删除入口必须经过 checkDelete 框架（行为上恒过），保证未来加规则时 remove 不用改。 */
    @Test
    void removeMaterial_shouldPassThroughCheck() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(new WmsDeleteCheckVO()).when(service).checkDelete("M1");
        doReturn(true).when(service).removeById("M1");

        service.removeMaterial("M1");

        verify(service).checkDelete("M1");
        verify(service).removeById("M1");
    }

    /** 无关联数据时按 id 直接删除（BD001-T13）。 */
    @Test
    void removeMaterial_shouldRemoveById() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(true).when(service).removeById("1");

        service.removeMaterial("1");

        verify(service).removeById("1");
    }

    /** 空/null 批量删除必须安全返回，不触发任何删除。 */
    @Test
    void removeBatch_shouldNoOpOnNullOrEmpty() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doNothing().when(service).removeMaterial(any(String.class));

        service.removeBatch(null);
        service.removeBatch(Collections.emptyList());

        verify(service, never()).removeMaterial(any(String.class));
    }

    /** 批量删除逐条走单条入口。 */
    @Test
    void removeBatch_shouldLoopSingleGuard() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doNothing().when(service).removeMaterial(any(String.class));

        service.removeBatch(Arrays.asList("M1", "M2"));

        verify(service).removeMaterial("M1");
        verify(service).removeMaterial("M2");
    }

    /** 批量预检：全部可删时返回可删、无明细。 */
    @Test
    void checkDeleteBatch_shouldBeDeletable() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));

        WmsBatchDeleteCheckVO vo = service.checkDeleteBatch(Arrays.asList("M1", "M2"));

        assertTrue(vo.isDeletable());
    }

    // ------------------------------------------------------------ 查重

    /** 空编码直接判为「不重复」，不查库。 */
    @Test
    void checkCodeExists_shouldShortCircuitOnBlankCode() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));

        assertFalse(service.checkCodeExists(null, null));
        assertFalse(service.checkCodeExists("", null));

        verify(service, never()).count(any(Wrapper.class));
    }

    /** 非空编码必须走计数查询。 */
    @Test
    void checkCodeExists_shouldQueryWhenCodePresent() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        doReturn(1L).when(service).count(any(Wrapper.class));

        assertTrue(service.checkCodeExists("M001", null));
    }

    // ------------------------------------------------------------ 分类回填（Review Focus #5）

    /** 空列表直接返回，不查库。 */
    @Test
    void fillCategoryInfo_shouldNoOpOnEmptyList() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);

        service.fillCategoryInfo(null);
        service.fillCategoryInfo(Collections.emptyList());

        verify(mapper, never()).selectBatchIds(any());
    }

    /** 全部分类ID为空时不应查库。 */
    @Test
    void fillCategoryInfo_shouldSkipQueryWhenNoCategoryId() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001");
        service.fillCategoryInfo(Collections.singletonList(material));

        verify(mapper, never()).selectBatchIds(any());
    }

    /** 分类存在时必须回填名称与编码。 */
    @Test
    void fillCategoryInfo_shouldFillNameAndCode() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);
        WmsMaterialCategory category = new WmsMaterialCategory()
                .setCategoryCode("CAT-BAN").setCategoryName("半成品");
        category.setId("C1");
        when(mapper.selectBatchIds(any())).thenReturn(Collections.singletonList(category));

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setCategoryId("C1");
        service.fillCategoryInfo(Collections.singletonList(material));

        assertEquals("半成品", material.getCategoryName());
        assertEquals("CAT-BAN", material.getCategoryCode());
    }

    /** 分类已被删除时回填必须静默留空，不得抛异常把整个列表打挂（Review Focus #5）。 */
    @Test
    void fillCategoryInfo_shouldTolerateMissingCategory() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);
        // selectBatchIds 自带 del_flag=0 过滤，已删除的分类查不到 → 返回空集合
        when(mapper.selectBatchIds(any())).thenReturn(Collections.emptyList());

        WmsMaterial material = new WmsMaterial().setMaterialCode("M001").setCategoryId("C-GONE");

        assertDoesNotThrow(() -> service.fillCategoryInfo(Collections.singletonList(material)));
        assertNull(material.getCategoryName());
    }

    // ------------------------------------------------------------ 按编码解析分类ID（BD001-T11 的真实落点）

    /** 空编码（null / ""）返回 null，不查库。 */
    @Test
    void resolveCategoryIdByCode_shouldReturnNullOnEmpty() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);

        assertNull(service.resolveCategoryIdByCode(null));
        assertNull(service.resolveCategoryIdByCode(""));

        verify(mapper, never()).selectOne(any(Wrapper.class));
    }

    /** 纯空白编码不算空（oConvertUtils.isEmpty 不 trim），会照常查库。 */
    @Test
    void resolveCategoryIdByCode_shouldQueryForWhitespaceCode() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertNull(service.resolveCategoryIdByCode("   "));

        verify(mapper).selectOne(any(Wrapper.class));
    }

    /** 找到分类时返回其 id。 */
    @Test
    void resolveCategoryIdByCode_shouldReturnIdWhenFound() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);
        WmsMaterialCategory category = new WmsMaterialCategory().setCategoryCode("CAT-BAN").setCategoryName("半成品");
        category.setId("C1");
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(category);

        assertEquals("C1", service.resolveCategoryIdByCode("CAT-BAN"));
    }

    /** 查不到时必须返回 null——导入路径据此报「分类编码 X 不存在」。 */
    @Test
    void resolveCategoryIdByCode_shouldReturnNullWhenNotFound() {
        WmsMaterialCategoryMapper mapper = mock(WmsMaterialCategoryMapper.class);
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mapper);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertNull(service.resolveCategoryIdByCode("NO_SUCH_CAT"));
    }

    // ------------------------------------------------------------ 库存引用守卫（RK-001）

    @Test
    void materialDeleteBlockedByInventory() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        org.jeecg.modules.wms.mapper.WmsInventoryMapper inv =
                mock(org.jeecg.modules.wms.mapper.WmsInventoryMapper.class);
        WmsTestSupport.inject(service, "inventoryMapper", inv);
        when(inv.selectCount(any())).thenReturn(3L);

        WmsDeleteCheckVO vo = service.checkDelete("m1");

        assertFalse(vo.isDeletable());
        assertTrue(vo.getBlockers().get(0).getMessage().contains("库存记录"));
        assertEquals(3, vo.getBlockers().get(0).getCount());
    }

    @Test
    void materialDeleteAllowedWhenNoInventory() {
        WmsMaterialServiceImpl service = serviceWithCategoryMapper(mock(WmsMaterialCategoryMapper.class));
        org.jeecg.modules.wms.mapper.WmsInventoryMapper inv =
                mock(org.jeecg.modules.wms.mapper.WmsInventoryMapper.class);
        WmsTestSupport.inject(service, "inventoryMapper", inv);
        when(inv.selectCount(any())).thenReturn(0L);

        assertTrue(service.checkDelete("m1").isDeletable());
    }
}
