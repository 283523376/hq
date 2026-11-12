package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterialCategory;
import org.jeecg.modules.wms.entity.WmsMaterialCategoryGroup;
import org.jeecg.modules.wms.mapper.WmsMaterialCategoryGroupMapper;
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
    private WmsMaterialCategoryGroupMapper categoryGroupMapper;
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
        categoryGroupMapper = mock(WmsMaterialCategoryGroupMapper.class);
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
        WmsTestSupport.inject(service, "categoryGroupMapper", categoryGroupMapper);
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
        // P2 兜底已删：孪生查重带 ne(自身id) 排除，mock 按「参数里是否含自身 id」区分两类查询。
        // 注意 MP 条件参数惰性填充：必须先渲染 SQL 片段，参数才进 paramNameValuePairs
        initCategoryTableInfoIfAbsent();
        when(categoryMapper.selectOne(any())).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) invocation.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("CAT1") ? null : existing;
        });
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getRenamed());
        verify(categoryMapper).updateById(existing);
        assertEquals("自制", existing.getCategoryName());
    }

    /**
     * 映射表命中（2026-11-11 分类合并后的常态）：多个分组指向同一分类，
     * 直接复用，**不新建、也不改名称**。
     */
    @Test
    void syncMaterialGroups_shouldReuseCategoryFromGroupMapping() {
        WmsMaterialCategoryGroup mapping = new WmsMaterialCategoryGroup()
                .setGroupNumber("HC.0101.0201.C")
                .setCategoryId("CAT-MERGED");
        when(categoryGroupMapper.selectOne(any())).thenReturn(mapping);
        WmsMaterialCategory survivor = new WmsMaterialCategory()
                .setCategoryCode("接头")
                .setCategoryName("接头");
        survivor.setId("CAT-MERGED");
        when(categoryMapper.selectById("CAT-MERGED")).thenReturn(survivor);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("HC.0101.0201.C", "接头")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(0, vo.getCreated(), "映射表命中的分组不能新建分类");
        verify(categoryMapper, never()).insert(any(WmsMaterialCategory.class));
        verify(categoryMapper, never()).updateById(any(WmsMaterialCategory.class));
    }

    /**
     * 2026-11-12 审计 R1：映射命中的分类类型为 NULL、组映射类型为「辅料」，
     * 而同名已有一条「辅料」分类时，**绝不能把前者改成辅料**（会立刻造出重复），
     * 正确动作是把分组指向那条已有分类。
     */
    @Test
    void syncMaterialGroups_shouldRepointGroupInsteadOfFlippingTypeIntoDuplicate() {
        WmsMaterialCategoryGroup mapping = new WmsMaterialCategoryGroup()
                .setGroupNumber("Y.P.019")
                .setCategoryId("CAT-A");
        when(categoryGroupMapper.selectOne(any())).thenReturn(mapping);
        WmsMaterialCategory noType = new WmsMaterialCategory()
                .setCategoryCode("Y.P.019")
                .setCategoryName("机器配件(国产)");
        noType.setId("CAT-A");
        when(categoryMapper.selectById("CAT-A")).thenReturn(noType);
        WmsMaterialCategory twin = new WmsMaterialCategory()
                .setCategoryCode("T.P.019")
                .setCategoryName("机器配件(国产)")
                .setCategoryType("辅料");
        twin.setId("CAT-TWIN");
        when(categoryMapper.selectOne(any())).thenReturn(twin);
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("Y.P.019", "辅料"));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("Y.P.019", "机器配件(国产)")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        verify(categoryMapper, never()).updateById(any(WmsMaterialCategory.class));
        assertEquals(1, vo.getRenamed(), "改指了映射行要按 UPDATED 计数（QA M1），不能算「无变化」");
        ArgumentCaptor<WmsMaterialCategoryGroup> captor =
                ArgumentCaptor.forClass(WmsMaterialCategoryGroup.class);
        verify(categoryGroupMapper).updateById(captor.capture());
        assertEquals("CAT-TWIN", captor.getValue().getCategoryId(),
                "冲突时应把分组改指到同名同类型的分类");
    }

    /**
     * 2026-11-12 正式 QA P1：「分组编码精确命中」路径同样要有孪生守卫。
     * 映射行被逻辑删除的分组会落到该分支（库里 Y.P.019 就是这种行）——
     * 分类类型 NULL + 映射类型「辅料」+ 同名已有「辅料」分类时，
     * 必须不改类型、把分组改指到孪生分类，而不是再造一条同键重复。
     */
    @Test
    void syncMaterialGroups_shouldRepointGroupWhenExactHitWouldFlipTypeIntoDuplicate() {
        // 映射表按真实语义建模：查无 → insert 落库 → 下次查得到（否则第二次 bindGroup 会插两行，
        // 真实 DB 里第二次是 update 改指）
        java.util.concurrent.atomic.AtomicReference<WmsMaterialCategoryGroup> storedMapping =
                new java.util.concurrent.atomic.AtomicReference<>();
        // captor 捕的是引用，行对象会被 update 原地改掉，所以插入值要在 insert 时单独记下
        java.util.concurrent.atomic.AtomicReference<String> insertedCategoryId =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(categoryGroupMapper.selectOne(any())).thenAnswer(invocation -> storedMapping.get());
        when(categoryGroupMapper.insert(any(WmsMaterialCategoryGroup.class))).thenAnswer(invocation -> {
            WmsMaterialCategoryGroup row = invocation.getArgument(0);
            storedMapping.set(row);
            insertedCategoryId.set(row.getCategoryId());
            return 1;
        });
        WmsMaterialCategory existing = new WmsMaterialCategory()
                .setCategoryCode("Y.P.019")
                .setCategoryName("机器配件(国产)");
        existing.setId("CAT-A");
        WmsMaterialCategory twin = new WmsMaterialCategory()
                .setCategoryCode("T.P.019")
                .setCategoryName("机器配件(国产)")
                .setCategoryType(WmsConstants.CATEGORY_TYPE_AUXILIARY);
        twin.setId("CAT-TWIN");
        // 第 1 次 selectOne：按分组编码精确命中 → existing；第 2 次：孪生查重 → twin
        when(categoryMapper.selectOne(any())).thenReturn(existing, twin);
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("Y.P.019", WmsConstants.CATEGORY_TYPE_AUXILIARY));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("Y.P.019", "机器配件(国产)")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        verify(categoryMapper, never()).updateById(any(WmsMaterialCategory.class));
        assertNull(existing.getCategoryType(), "精确命中分支同样不能翻转类型");
        assertEquals(1, vo.getRenamed(), "改指按 UPDATED 计数（QA M1）");
        // 第一次 bindGroup 插入指向原分类，孪生守卫随后把它改指到孪生（真实 DB 为 update 语义）
        verify(categoryGroupMapper).insert(any(WmsMaterialCategoryGroup.class));
        assertEquals("CAT-A", insertedCategoryId.get(), "首次绑定应指向精确命中的分类");
        ArgumentCaptor<WmsMaterialCategoryGroup> repoint =
                ArgumentCaptor.forClass(WmsMaterialCategoryGroup.class);
        verify(categoryGroupMapper).updateById(repoint.capture());
        assertEquals("CAT-TWIN", repoint.getValue().getCategoryId(),
                "冲突时应把分组改指到同名同类型的分类");
    }

    /**
     * QA M5：finally 必须释放在途标记——第一轮成功跑完后，第二轮不得报「正在进行中」。
     * （若 finally 被误删，此后所有同步永远失败，而仅测一轮的用例不会红。）
     */
    @Test
    void syncMaterialGroups_shouldReleaseRunFlagAfterSuccess() {
        when(categoryGroupMapper.selectOne(any())).thenReturn(null);
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制")), null, null));

        service.syncMaterialGroups();
        WmsKingdeeStockSyncResultVO second = service.syncMaterialGroups();

        assertEquals(1, second.getCreated(), "第二轮必须正常执行而非被并发闸门拒绝");
    }

    /**
     * QA M5：applyMappedType 无孪生的主成功路径——映射命中且类型不同、无同名同类型孪生时，
     * 正常改类型并按 UPDATED 计数。
     */
    @Test
    void syncMaterialGroups_shouldUpdateTypeWhenNoTwin() {
        WmsMaterialCategoryGroup mapping = new WmsMaterialCategoryGroup()
                .setGroupNumber("Y.P.019")
                .setCategoryId("CAT-A");
        when(categoryGroupMapper.selectOne(any())).thenReturn(mapping);
        WmsMaterialCategory category = new WmsMaterialCategory()
                .setCategoryCode("Y.P.019")
                .setCategoryName("机器配件(国产)");
        category.setId("CAT-A");
        when(categoryMapper.selectById("CAT-A")).thenReturn(category);
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("Y.P.019", WmsConstants.CATEGORY_TYPE_AUXILIARY));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("Y.P.019", "机器配件(国产)")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(WmsConstants.CATEGORY_TYPE_AUXILIARY, category.getCategoryType());
        verify(categoryMapper).updateById(category);
        assertEquals(1, vo.getRenamed(), "无孪生时正常改类型，按 UPDATED 计数");
    }

    /**
     * QA M5：映射指向已删除分类 → 按未知分组处理（走新建），映射失效不能阻断同步。
     */
    @Test
    void syncMaterialGroups_shouldTreatDeletedCategoryMappingAsUnknownGroup() {
        WmsMaterialCategoryGroup mapping = new WmsMaterialCategoryGroup()
                .setGroupNumber("HC.0101.0201.C")
                .setCategoryId("CAT-DELETED");
        when(categoryGroupMapper.selectOne(any())).thenReturn(mapping);
        when(categoryMapper.selectById("CAT-DELETED")).thenReturn(null);
        when(categoryMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("HC.0101.0201.C", "接头")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(1, vo.getCreated(), "映射失效按未知分组新建，不阻断");
        verify(categoryMapper).insert(any(WmsMaterialCategory.class));
        // 新分类 id 由 MyBatis-Plus 生成（mock 里为 null），bindGroup 的空 id 守卫应跳过写映射
        verify(categoryGroupMapper, never()).insert(any(WmsMaterialCategoryGroup.class));
    }

    /**
     * QA M2：映射查询本身失败 → 本轮中止（抛出），绝不降级为「未知分组」继续新建；
     * 异常路径同样要释放在途标记（下一轮可重跑）。
     */
    @Test
    void syncMaterialGroups_shouldAbortWhenMappingQueryFails() {
        when(categoryGroupMapper.selectOne(any())).thenThrow(new RuntimeException("DB 抖动"));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("HC.0101.0201.C", "接头")), null, null));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.syncMaterialGroups());

        assertTrue(error.getMessage().contains("中止"), error.getMessage());
        verify(categoryMapper, never()).insert(any(WmsMaterialCategory.class));
        // 异常路径释放标记：直接再跑一次配置失败分支（不碰金蝶），不得报「正在进行中」
        properties.setEnabled(false);
        RuntimeException second = assertThrows(RuntimeException.class, () -> service.syncMaterialGroups());
        assertTrue(second.getMessage().contains("未开启"), second.getMessage());
    }

    /**
     * 2026-11-12 正式 QA P2：排除自身必须在 SQL 层（ne(id)）完成——mock 兜底已删。
     * 用真实 wrapper 渲染断言 ne 子句存在且带自身 id，防止有人删掉排除子句而测试不红。
     */
    @Test
    void findByNameAndType_shouldExcludeSelfAtSqlLayer() throws Exception {
        initCategoryTableInfoIfAbsent();
        WmsMaterialCategoryGroup mapping = new WmsMaterialCategoryGroup()
                .setGroupNumber("Y.P.019")
                .setCategoryId("CAT-A");
        when(categoryGroupMapper.selectOne(any())).thenReturn(mapping);
        WmsMaterialCategory category = new WmsMaterialCategory()
                .setCategoryCode("Y.P.019")
                .setCategoryName("机器配件(国产)");
        category.setId("CAT-A");
        when(categoryMapper.selectById("CAT-A")).thenReturn(category);
        java.util.concurrent.atomic.AtomicReference<
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WmsMaterialCategory>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(categoryMapper.selectOne(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        });
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("Y.P.019", WmsConstants.CATEGORY_TYPE_AUXILIARY));
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("Y.P.019", "机器配件(国产)")), null, null));

        service.syncMaterialGroups();

        var wrapper = captured.get();
        org.junit.jupiter.api.Assertions.assertNotNull(wrapper, "孪生查重必须发起按名称+类型的查询");
        String sql = wrapper.getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("<>"),
                "排除自身必须在 SQL 层完成（ne id），实际片段：" + sql);
        org.junit.jupiter.api.Assertions.assertTrue(
                wrapper.getParamNameValuePairs().containsValue("CAT-A"),
                "排除的必须是自身 id，实际参数：" + wrapper.getParamNameValuePairs());
    }

    /**
     * 2026-11-12 审计 R3：ERP 改组名撞上另一条同名同类型分类时，
     * 保持原名、不静默造重复。
     */
    @Test
    void syncMaterialGroups_shouldKeepOldNameWhenRenameWouldDuplicate() {
        WmsMaterialCategory existing = new WmsMaterialCategory()
                .setCategoryCode("XA.00.01010101")
                .setCategoryName("旧名称");
        existing.setId("CAT1");
        existing.setErpGroupNumber("XA.00.01010101");
        existing.setErpGroupName("新名称");
        WmsMaterialCategory twin = new WmsMaterialCategory()
                .setCategoryCode("OTHER")
                .setCategoryName("新名称");
        twin.setId("CAT2");
        // 第一次 selectOne：分组编码精确命中 → existing；第二次：改名查重 → twin
        when(categoryMapper.selectOne(any())).thenReturn(existing).thenReturn(twin);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "新名称")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals("旧名称", existing.getCategoryName(), "撞名时不能改名");
        assertEquals(0, vo.getRenamed());
        verify(categoryMapper, never()).updateById(any(WmsMaterialCategory.class));
    }

    /**
     * 未知分组但已有同名同类型分类：复用并补映射，不再建出第 9 个「接头」。
     * 这是修复重复分类的核心分支。
     */
    @Test
    void syncMaterialGroups_shouldReuseSameNameAndTypeCategoryInsteadOfDuplicating() {
        when(groupMapService.typeByGroupNumber())
                .thenReturn(Map.of("HC.0101.0201.C", WmsConstants.CATEGORY_TYPE_RAW));
        WmsMaterialCategory existing = new WmsMaterialCategory()
                .setCategoryCode("接头")
                .setCategoryName("接头")
                .setCategoryType(WmsConstants.CATEGORY_TYPE_RAW);
        existing.setId("CAT1");
        // 第一次 selectOne：按分组编码/分类编码精确命中 → 无；第二次：按名称+类型 → 命中
        when(categoryMapper.selectOne(any())).thenReturn(null).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("HC.0101.0201.C", "接头")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(0, vo.getCreated(), "同名同类型已存在时不能再建分类");
        verify(categoryMapper, never()).insert(any(WmsMaterialCategory.class));
        ArgumentCaptor<WmsMaterialCategoryGroup> captor =
                ArgumentCaptor.forClass(WmsMaterialCategoryGroup.class);
        verify(categoryGroupMapper).insert(captor.capture());
        assertEquals("HC.0101.0201.C", captor.getValue().getGroupNumber());
        assertEquals("CAT1", captor.getValue().getCategoryId(), "复用时要补写分组→分类映射");
    }

    /** 真正的新分组：建分类的同时写映射，下一轮同步即可命中映射表。 */
    @Test
    void syncMaterialGroups_shouldBindCreatedCategoryToGroup() {
        when(categoryMapper.selectOne(any())).thenReturn(null);
        // 真 MyBatis-Plus 在 insert 时按 ASSIGN_ID 填主键，mock 里手动补上
        when(categoryMapper.insert(any(WmsMaterialCategory.class))).thenAnswer(invocation -> {
            WmsMaterialCategory created = invocation.getArgument(0);
            created.setId("CAT-NEW");
            return 1;
        });
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(
                        List.of("XA.00.01010101", "自制")), null, null));

        WmsKingdeeStockSyncResultVO vo = service.syncMaterialGroups();

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsMaterialCategoryGroup> captor =
                ArgumentCaptor.forClass(WmsMaterialCategoryGroup.class);
        verify(categoryGroupMapper).insert(captor.capture());
        assertEquals("XA.00.01010101", captor.getValue().getGroupNumber());
        assertEquals("自制", captor.getValue().getGroupName());
        assertEquals("CAT-NEW", captor.getValue().getCategoryId());
        assertEquals(0L, captor.getValue().getDeletedAt().longValue(), "新映射行必须是未删除");
    }

    /**
     * 2026-11-12 实测触发：全量同步约 30s，前端 10s 超时后用户会再点一次，
     * 两次并发都会走「查不到 → 新建」，必须让第二个直接失败。
     */
    @Test
    void syncMaterialGroups_shouldRejectConcurrentRun() throws Exception {
        java.lang.reflect.Field field = WmsKingdeeCategorySyncServiceImpl.class
                .getDeclaredField("materialGroupsRunning");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) field.get(service)).set(true);
        try {
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.syncMaterialGroups());
            assertTrue(error.getMessage().contains("正在进行中"), error.getMessage());
            verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt(), anyInt());
        } finally {
            ((java.util.concurrent.atomic.AtomicBoolean) field.get(service)).set(false);
        }
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

    /**
     * LambdaQueryWrapper 渲染 SQL 片段（getSqlSegment）与惰性参数填充都依赖实体的
     * TableInfo 缓存；纯 Mockito 环境没有启动类，需手动初始化一次（全局缓存，幂等）。
     */
    private static void initCategoryTableInfoIfAbsent() {
        if (com.baomidou.mybatisplus.core.metadata.TableInfoHelper
                .getTableInfo(WmsMaterialCategory.class) == null) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    WmsMaterialCategory.class);
        }
    }

    private static List<List<Object>> rows(List<Object>... items) {
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> item : items) {
            rows.add(new ArrayList<>(item));
        }
        return rows;
    }
}
