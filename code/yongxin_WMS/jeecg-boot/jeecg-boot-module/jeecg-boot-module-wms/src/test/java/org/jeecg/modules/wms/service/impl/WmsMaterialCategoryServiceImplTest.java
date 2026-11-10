package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.vo.WmsDeleteCheckVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 物料分类业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
class WmsMaterialCategoryServiceImplTest {

    /** 造一个带 id 的分类。 */
    private static WmsMaterialCategory category(String id, String code, String name, String parentId) {
        WmsMaterialCategory c = new WmsMaterialCategory().setCategoryCode(code).setCategoryName(name);
        c.setId(id);
        c.setParentId(parentId);
        return c;
    }

    // ------------------------------------------------------------ 分类树（BD001-T01）

    /** 两层结构必须正确挂接到父节点下，且 isLeaf 反映「有没有子节点」。 */
    @Test
    void queryTreeList_shouldBuildHierarchy() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        WmsMaterialCategory root = category("1", "C01", "半成品", WmsConstants.ROOT_PID_VALUE);
        WmsMaterialCategory child = category("2", "C0101", "焊接件", "1");
        doReturn(Arrays.asList(child, root)).when(service).list(any(Wrapper.class));

        List<SelectTreeModel> tree = service.queryTreeList();

        assertEquals(1, tree.size());
        SelectTreeModel rootModel = tree.get(0);
        assertEquals("1", rootModel.getKey());
        assertEquals("1", rootModel.getValue());
        assertEquals("半成品", rootModel.getTitle());
        assertEquals(WmsConstants.ROOT_PID_VALUE, rootModel.getParentId());
        // SelectTreeModel 的布尔字段是 isLeaf，getter 为 isLeaf()（没有 getLeaf()）
        assertFalse(rootModel.isLeaf(), "有子节点的根不应是叶子");

        assertEquals(1, rootModel.getChildren().size());
        SelectTreeModel childModel = rootModel.getChildren().get(0);
        assertEquals("2", childModel.getKey());
        assertEquals("1", childModel.getParentId());
        assertTrue(childModel.isLeaf(), "无子节点应是叶子");
        assertTrue(childModel.getChildren().isEmpty());
    }

    /** 空数据必须返回空树，不能 NPE（BD001-T01 的空数据分支）。 */
    @Test
    void queryTreeList_shouldReturnEmptyOnNoData() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));

        assertTrue(service.queryTreeList().isEmpty());
    }

    /** parentId 为 null/空时必须按根节点（"0"）归组，否则顶层分类会从树上消失。 */
    @Test
    void queryTreeList_shouldTreatBlankParentIdAsRoot() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        WmsMaterialCategory orphan = category("1", "C01", "无父分类", null);
        doReturn(Collections.singletonList(orphan)).when(service).list(any(Wrapper.class));

        List<SelectTreeModel> tree = service.queryTreeList();

        assertEquals(1, tree.size(), "parentId 为空的分类必须出现在根层");
        assertEquals(WmsConstants.ROOT_PID_VALUE, tree.get(0).getParentId());
    }

    // ------------------------------------------------------------ 新增（BD001-T03/T04/T05）

    /** 新增时状态/是否有子节点/父节点都要落默认值，并回写父节点 has_child（BD001-T03）。 */
    @Test
    void addCategory_shouldApplyDefaultsAndMarkParent() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());
        WmsMaterialCategory parent = category("P1", "P", "父分类", WmsConstants.ROOT_PID_VALUE);
        doReturn(parent).when(service).getById("P1");
        doReturn(true).when(service).updateById(any());

        WmsMaterialCategory input = category(null, "C01", "半成品", "P1");
        service.addCategory(input);

        assertEquals(WmsConstants.STATUS_ENABLE, input.getStatus());
        assertEquals(WmsConstants.NO, input.getHasChild());
        assertNotNull(input.getCreateTime(), "审计字段必须由 WmsUtils 显式写入");
        assertEquals(WmsConstants.YES, parent.getHasChild(), "父节点的 has_child 必须被回写为 1");
        verify(service).save(input);
        verify(service).updateById(parent);
    }

    /** 顶层分类（父节点为根）不得去查、去改父节点——根节点是虚拟的。 */
    @Test
    void addCategory_shouldNotTouchParentWhenRoot() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());

        WmsMaterialCategory input = category(null, "C01", "顶层分类", null);
        service.addCategory(input);

        assertEquals(WmsConstants.ROOT_PID_VALUE, input.getParentId(), "空父节点应落为 0");
        verify(service, never()).getById(any());
        verify(service, never()).updateById(any());
    }

    /** 父节点已是 has_child=1 时不应产生多余的 UPDATE。 */
    @Test
    void addCategory_shouldSkipParentUpdateWhenAlreadyHasChild() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());
        WmsMaterialCategory parent = category("P1", "P", "父分类", WmsConstants.ROOT_PID_VALUE)
                .setHasChild(WmsConstants.YES);
        doReturn(parent).when(service).getById("P1");

        service.addCategory(category(null, "C01", "半成品", "P1"));

        verify(service, never()).updateById(any());
    }

    /** 编码重复必须拒绝（BD001-T04）。 */
    @Test
    void addCategory_shouldRejectDuplicatedCode() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addCategory(category(null, "C01", "半成品", null)));

        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    /**
     * ⚠ 现状记录（BD001-T05 缺口 G-05）：父节点不存在时**不报错**，静默跳过回写。
     *
     * <p>本用例固化当前行为，用于在将来补上校验（改为抛「父节点不存在」）时立即变红提醒更新。
     */
    @Test
    void addCategory_shouldTolerateMissingParent() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).save(any());
        doReturn(null).when(service).getById("不存在的父节点");

        WmsMaterialCategory input = category(null, "C01", "半成品", "不存在的父节点");

        service.addCategory(input);

        verify(service).save(input);
        verify(service, never()).updateById(any());
    }

    // ------------------------------------------------------------ 编辑（BD001-T06）

    /** 编辑时排除自身后编码可用，即允许「改名称不改编码」。 */
    @Test
    void editCategory_shouldUpdateWhenCodeFree() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(false).when(service).checkCodeExists(any(), any());
        doReturn(true).when(service).updateById(any());

        WmsMaterialCategory input = category("1", "C01", "半成品（改名）", WmsConstants.ROOT_PID_VALUE);
        service.editCategory(input);

        assertNotNull(input.getUpdateTime());
        verify(service).checkCodeExists("C01", "1");
        verify(service).updateById(input);
    }

    /** 编辑时编码撞上别的分类必须拒绝（BD001-T04 的编辑分支）。 */
    @Test
    void editCategory_shouldRejectDuplicatedCode() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(true).when(service).checkCodeExists(any(), any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.editCategory(category("1", "C01", "半成品", null)));

        assertEquals("编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).updateById(any());
    }

    // ------------------------------------------------------------ 停用（BD001-T08）

    /** 停用只需一条更新，且把状态置为 0。 */
    @Test
    void disableCategory_shouldSetStatusDisabled() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(true).when(service).updateById(any());

        service.disableCategory("1");

        ArgumentCaptor<WmsMaterialCategory> captor = ArgumentCaptor.forClass(WmsMaterialCategory.class);
        verify(service).updateById(captor.capture());
        assertEquals("1", captor.getValue().getId());
        assertEquals(WmsConstants.STATUS_DISABLE, captor.getValue().getStatus());
    }

    // ------------------------------------------------------------ 删除守卫与预检（BD001-T07，spec 2026-10-16 §3.2）

    /** 构造已注入 mock 物料 mapper 的 service：守卫查询 wms_material.category_id。 */
    private WmsMaterialCategoryServiceImpl guardService(long materialCount) {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        WmsMaterialMapper materialMapper = mock(WmsMaterialMapper.class);
        WmsTestSupport.inject(service, "wmsMaterialMapper", materialMapper);
        doReturn(materialCount).when(materialMapper).selectCount(any(Wrapper.class));
        return service;
    }

    /** 分类下有物料必须拒绝删除（BD001-T07）。 */
    @Test
    void removeCategory_shouldRejectWhenHasMaterial() {
        WmsMaterialCategoryServiceImpl service = guardService(1L);
        doReturn(0L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeCategory("1"));

        assertEquals("该分类下存在物料，不允许删除", ex.getMessage());
        verify(service, never()).removeById(any());
    }

    /** 分类下有子分类必须拒绝删除——否则子分类会变成孤儿。 */
    @Test
    void removeCategory_shouldRejectWhenHasChildren() {
        WmsMaterialCategoryServiceImpl service = guardService(0L);
        doReturn(2L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeCategory("1"));

        assertEquals("该分类下存在子分类，不允许删除", ex.getMessage());
        verify(service, never()).removeById(any());
    }

    /** 既无物料也无子分类时才允许删除。 */
    @Test
    void removeCategory_shouldRemoveWhenClean() {
        WmsMaterialCategoryServiceImpl service = guardService(0L);
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).removeById("1");

        service.removeCategory("1");

        verify(service).removeById("1");
    }

    /** 预检必须一次报全物料与子分类两 blocker（顺序：物料在前，与删除文案优先级一致）。 */
    @Test
    void checkDelete_shouldReportBothBlockersInOrder() {
        WmsMaterialCategoryServiceImpl service = guardService(3L);
        doReturn(2L).when(service).count(any(Wrapper.class));

        WmsDeleteCheckVO vo = service.checkDelete("C1");

        assertFalse(vo.isDeletable());
        assertEquals(2, vo.getBlockers().size());
        assertEquals("该分类下存在物料", vo.getBlockers().get(0).getMessage());
        assertEquals("该分类下存在子分类", vo.getBlockers().get(1).getMessage());
    }

    // ------------------------------------------------------------ 查重

    /** 空编码直接判为「不重复」，不查库——避免为空值拼出 `category_code is null` 之类的条件。 */
    @Test
    void checkCodeExists_shouldShortCircuitOnBlankCode() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());

        assertFalse(service.checkCodeExists(null, null));
        assertFalse(service.checkCodeExists("", null));

        verify(service, never()).count(any(Wrapper.class));
    }

    /** 非空编码必须走计数查询。 */
    @Test
    void checkCodeExists_shouldQueryWhenCodePresent() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(1L).when(service).count(any(Wrapper.class));

        assertTrue(service.checkCodeExists("C01", null));
    }

    // ------------------------------------------------------------ 分类下是否有物料

    // 注：原先的 hasMaterial_shouldShortCircuitOnBlankId / hasMaterial_shouldQueryMaterialMapper
    // 随 hasMaterial 一并删除（2026-10-16）—— 守卫提取为 checkDelete 后该方法已无生产调用方，
    // 保留它会让「往 hasMaterial 里加规则」被误认为影响删除守卫。物料计数现由
    // checkDelete（及下方 checkDelete_* 用例）走 wmsMaterialMapper.selectCount 验证。

    // ------------------------------------------------------------ 按编码取ID

    /** 空编码（null / ""）返回 null，不查库。 */
    @Test
    void queryIdByCode_shouldReturnNullOnEmpty() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());

        assertNull(service.queryIdByCode(null));
        assertNull(service.queryIdByCode(""));

        verify(service, never()).getOne(any(Wrapper.class), eq(false));
    }

    /**
     * 纯空白编码**不算空**——{@code oConvertUtils.isEmpty} 不 trim，故会照常查库。
     *
     * <p>与 {@code checkCodeExists} 的短路口径一致。此处刻意固化它，
     * 免得将来有人以为「空白会被短路」（写计划时我自己就先这么误判了一次）。
     */
    @Test
    void queryIdByCode_shouldQueryForWhitespaceCode() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(null).when(service).getOne(any(Wrapper.class), eq(false));

        assertNull(service.queryIdByCode("   "));

        verify(service).getOne(any(Wrapper.class), eq(false));
    }

    /** 找到分类时返回其 id。 */
    @Test
    void queryIdByCode_shouldReturnIdWhenFound() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(category("C9", "C01", "半成品", WmsConstants.ROOT_PID_VALUE))
                .when(service).getOne(any(Wrapper.class), eq(false));

        assertEquals("C9", service.queryIdByCode("C01"));
    }

    /** 查不到时返回 null（供导入路径报「分类编码 X 不存在」）。 */
    @Test
    void queryIdByCode_shouldReturnNullWhenNotFound() {
        WmsMaterialCategoryServiceImpl service = spy(new WmsMaterialCategoryServiceImpl());
        doReturn(null).when(service).getOne(any(Wrapper.class), eq(false));

        assertNull(service.queryIdByCode("NO_SUCH_CAT"));
    }
}