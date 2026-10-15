package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.common.system.vo.SelectTreeModel;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsDevice;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 库区业务单测（不启 Spring 上下文、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsWarehouseServiceImplTest {

    private static WmsWarehouse warehouse(String id, String code, String name, String type) {
        WmsWarehouse w = new WmsWarehouse().setWarehouseCode(code).setWarehouseName(name).setWarehouseType(type);
        w.setId(id);
        return w;
    }

    /** 造一个注入了 mapper 的 spy，mapper 默认「无引用」，避免个别用例漏配导致 NPE。 */
    private static WmsWarehouseServiceImpl newService() {
        WmsWarehouseServiceImpl service = spy(new WmsWarehouseServiceImpl());
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsDeviceMapper deviceMapper = mock(WmsDeviceMapper.class);
        when(locationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(deviceMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        WmsTestSupport.inject(service, "wmsLocationMapper", locationMapper);
        WmsTestSupport.inject(service, "wmsDeviceMapper", deviceMapper);
        return service;
    }

    // ------------------------------------------------------------ 编码唯一性（蓝图步骤 3）

    @Test
    void addWarehouse_shouldRejectDuplicateCode() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(1L).when(service).count(any(Wrapper.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.addWarehouse(warehouse(null, "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO)));
        assertEquals("库区编码已存在，请重新输入", ex.getMessage());
        verify(service, never()).save(any());
    }

    @Test
    void addWarehouse_shouldDefaultStatusToEnable() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).save(any());

        service.addWarehouse(warehouse(null, "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO));

        ArgumentCaptor<WmsWarehouse> captor = ArgumentCaptor.forClass(WmsWarehouse.class);
        verify(service).save(captor.capture());
        assertEquals(WmsConstants.STATUS_ENABLE, captor.getValue().getStatus());
        assertTrue(captor.getValue().getCreateTime() != null, "审计字段应由 WmsUtils.fillCreate 填入");
    }

    /** 编辑时唯一性校验必须排除自身，否则原地保存会被自己挡住。 */
    @Test
    void editWarehouse_shouldExcludeSelfOnCodeCheck() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(1L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());
        doReturn(true).when(service).update(any(), any(Wrapper.class));

        // count 返回 1 表示「该编码已被别人占用」→ 应拒绝
        WmsWarehouse self = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        assertThrows(RuntimeException.class, () -> service.editWarehouse(self));
        verify(service, never()).update(any(), any(Wrapper.class));

        doReturn(0L).when(service).count(any(Wrapper.class));
        service.editWarehouse(self);
        verify(service).update(any(), any(Wrapper.class));
    }

    // ------------------------------------------------------------ 库区树（蓝图步骤 2）

    @Test
    void queryTreeList_shouldGroupByWarehouseType() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(Arrays.asList(
                warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO),
                warehouse("2", "MJ", "成品敏捷仓", WmsConstants.WAREHOUSE_TYPE_AGILE),
                warehouse("3", "LB2", "立库二号", WmsConstants.WAREHOUSE_TYPE_STEREO)
        )).when(service).list(any(Wrapper.class));

        List<SelectTreeModel> tree = service.queryTreeList();

        assertEquals(2, tree.size(), "两个类型 → 两个顶层节点");
        SelectTreeModel first = tree.get(0);
        assertEquals(WmsConstants.WAREHOUSE_TYPE_STEREO, first.getTitle());
        assertEquals("type:立库", first.getKey(), "类型节点 key 必须带前缀，避免与库区真实 id 撞键");
        assertEquals(WmsConstants.ROOT_PID_VALUE, first.getParentId());
        assertFalse(first.isLeaf());
        assertEquals(2, first.getChildren().size());
        assertEquals("1", first.getChildren().get(0).getKey());
        assertEquals("半成品立库", first.getChildren().get(0).getTitle());
        assertTrue(first.getChildren().get(0).isLeaf());
    }

    @Test
    void queryTreeList_shouldReturnEmptyWhenNoWarehouse() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        assertTrue(service.queryTreeList().isEmpty());
    }

    // ------------------------------------------------------------ 维度自由增减（spec §5.2 核心）

    /**
     * 需求方明确要求：**所有维度可自由调节**（可大也可小），不得有任何守卫。
     *
     * <p>本用例是对初稿设计的**反向断言** —— 初稿曾禁止缩维与跨段宽扩容。若哪天有人
     * 「为了编码稳定」又把这层守卫加回来，本用例会红。
     */
    @Test
    void editWarehouse_shouldAllowShrinkingAndGrowingDimensionsFreely() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());
        doReturn(true).when(service).update(any(), any(Wrapper.class));

        WmsWarehouse shrinking = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        shrinking.setRowCount(1).setColCount(6).setLayerCount(10);   // 由 24 缩到 10
        service.editWarehouse(shrinking);

        WmsWarehouse growing = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        growing.setRowCount(1).setColCount(60422).setLayerCount(9999); // 扩到跨段宽
        service.editWarehouse(growing);

        verify(service, org.mockito.Mockito.times(2)).update(any(), any(Wrapper.class));
    }

    /**
     * 维度清空必须**真的写进 NULL**。
     *
     * <p>端到端验收抓到过这个缺陷：{@code updateById} 默认忽略 null 字段，于是
     * 「把三个维度清空」会返回保存成功而库里纹丝不动 —— 与解绑、清空告警节流是同一类
     * 静默故障。故编辑库区改用 {@code UpdateWrapper} 显式 set 三列。
     *
     * <p>该用例断言 SET 子句里出现三列 —— 这是 mock 层面能表达的最强证据；
     * 真值由端到端步骤 18e 在真库上验证。
     */
    @Test
    void editWarehouse_shouldWriteNullDimensionsThroughUpdateWrapper() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).update(any(), any(Wrapper.class));
        doReturn(true).when(service).updateById(any());

        WmsWarehouse lineSide = warehouse("1", "XB", "线边柜", WmsConstants.WAREHOUSE_TYPE_LINE_SIDE);
        lineSide.setRowCount(null).setColCount(null).setLayerCount(null);
        service.editWarehouse(lineSide);

        ArgumentCaptor<Wrapper<WmsWarehouse>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(any(), captor.capture());
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsWarehouse>) captor.getValue())
                .getSqlSet();
        assertTrue(sqlSet.contains("row_count"), "实际 SET：" + sqlSet);
        assertTrue(sqlSet.contains("col_count"), "实际 SET：" + sqlSet);
        assertTrue(sqlSet.contains("layer_count"), "实际 SET：" + sqlSet);
    }

    /**
     * **显式 set 的列必须只有那三个维度**，其余字段一律走 {@code updateById}。
     *
     * <p>这是对一次真实回归的钉死：为了让「清空维度」写进 NULL，第一版把**所有列**都显式 set 了，
     * 结果编辑请求只要漏传某个 NOT NULL 字段（如 {@code status}），SQL 就会写 null 并抛
     * {@code Column 'status' cannot be null} —— 而原先的 {@code updateById} 会跳过 null，
     * 漏传字段本来是安全的。
     *
     * <p>「必须能写 null」（维度）与「null 表示未提供」（其它字段）是两个冲突的要求，
     * 故拆成两条语句：维度显式 set，其余沿用 updateById 的跳过语义。
     * 若有人图省事把两处合并回一个大 set，本用例会红。
     */
    @Test
    void editWarehouse_mustNotExplicitlySetNonDimensionColumns() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any());
        doReturn(true).when(service).update(any(), any(Wrapper.class));

        // 模拟表单漏传 status 的情形
        WmsWarehouse partial = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        partial.setRowCount(1).setColCount(6).setLayerCount(10);
        partial.setStatus(null);
        service.editWarehouse(partial);

        ArgumentCaptor<Wrapper<WmsWarehouse>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).update(any(), captor.capture());
        String sqlSet = ((com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WmsWarehouse>) captor.getValue())
                .getSqlSet();
        assertFalse(sqlSet.contains("status"), "漏传的字段不得被显式 set 成 null，实际 SET：" + sqlSet);
        assertFalse(sqlSet.contains("warehouse_code"), "实际 SET：" + sqlSet);
        assertFalse(sqlSet.contains("warehouse_name"), "实际 SET：" + sqlSet);
        assertFalse(sqlSet.contains("warehouse_type"), "实际 SET：" + sqlSet);
    }

    // ------------------------------------------------------------ 删除守卫

    @Test
    void removeWarehouse_shouldRejectWhenLocationsExist() {
        WmsWarehouseServiceImpl service = newService();
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        when(locationMapper.selectCount(any(Wrapper.class))).thenReturn(144L);
        WmsTestSupport.inject(service, "wmsLocationMapper", locationMapper);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeWarehouse("1"));
        assertEquals("该库区下存在库位，不允许删除", ex.getMessage());
        verify(service, never()).removeById(any(String.class));
    }

    @Test
    void removeWarehouse_shouldRejectWhenDevicesBound() {
        WmsWarehouseServiceImpl service = newService();
        WmsDeviceMapper deviceMapper = mock(WmsDeviceMapper.class);
        when(deviceMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        WmsTestSupport.inject(service, "wmsDeviceMapper", deviceMapper);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.removeWarehouse("1"));
        assertEquals("该库区已绑定设备，不允许删除", ex.getMessage());
    }

    @Test
    void removeWarehouse_shouldDeleteWhenNothingReferences() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(true).when(service).removeById(any(String.class));
        service.removeWarehouse("1");
        verify(service).removeById("1");
    }

    // ------------------------------------------------------------ requireEnabledWarehouse（T5 / T9 复用）

    @Test
    void requireEnabledWarehouse_shouldRejectMissing() {
        WmsWarehouseServiceImpl service = newService();
        doReturn(null).when(service).getById("nope");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.requireEnabledWarehouse("nope"));
        assertEquals("库区不存在或已停用", ex.getMessage());
    }

    @Test
    void requireEnabledWarehouse_shouldRejectDisabled() {
        WmsWarehouseServiceImpl service = newService();
        WmsWarehouse disabled = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        disabled.setStatus(WmsConstants.STATUS_DISABLE);
        doReturn(disabled).when(service).getById("1");
        assertThrows(RuntimeException.class, () -> service.requireEnabledWarehouse("1"));
    }

    @Test
    void requireEnabledWarehouse_shouldReturnEnabled() {
        WmsWarehouseServiceImpl service = newService();
        WmsWarehouse enabled = warehouse("1", "LB", "半成品立库", WmsConstants.WAREHOUSE_TYPE_STEREO);
        enabled.setStatus(WmsConstants.STATUS_ENABLE);
        doReturn(enabled).when(service).getById("1");
        assertEquals("1", service.requireEnabledWarehouse("1").getId());
    }
}