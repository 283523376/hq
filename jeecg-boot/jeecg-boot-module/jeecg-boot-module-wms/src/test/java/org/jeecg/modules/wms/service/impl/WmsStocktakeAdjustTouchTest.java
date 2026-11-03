package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点调整必须刷新库存行审计时间（SB-001 前置修复）。
 *
 * <p>「今日变动」对账按 {@code wms_inventory.update_time} 取数。本项目没有
 * MyBatis-Plus 自动填充（无 MetaObjectHandler），盘点更新路径此前走通用
 * {@code updateById}，update_time/update_by 从不变化——对账永远漏掉盘点调整。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsStocktakeAdjustTouchTest {

    private WmsStocktakeOrderMapper orderMapper;
    private WmsStocktakeDetailMapper detailMapper;
    private WmsInventoryMapper inventoryMapper;
    private WmsStocktakeServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsStocktakeOrderMapper.class);
        detailMapper = mock(WmsStocktakeDetailMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        service = new WmsStocktakeServiceImpl(orderMapper, detailMapper, inventoryMapper,
                mock(WmsLocationMapper.class), new WmsStocktakeStatusResolver());
    }

    private void stubAdjustableOrder() {
        WmsStocktakeOrder order = new WmsStocktakeOrder();
        order.setId("st-1");
        order.setStatus(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED);
        when(orderMapper.selectById("st-1")).thenReturn(order);
    }

    private WmsStocktakeDetail detail(String actual) {
        WmsStocktakeDetail detail = new WmsStocktakeDetail();
        detail.setId("d1");
        detail.setStocktakeId("st-1");
        detail.setLocationId("loc-1");
        detail.setMaterialId("m1");
        detail.setBatchNo("B1");
        detail.setBookQty(new BigDecimal("10"));
        detail.setActualQty(new BigDecimal(actual));
        return detail;
    }

    private WmsInventory existingRow() {
        WmsInventory existing = new WmsInventory();
        existing.setId("inv-1");
        return existing;
    }

    /** 更新路径：库存行存在、实盘 5 → touchQuantity(id, 5, 操作人)，绝不再走 updateById。 */
    @Test
    void adjustShouldTouchQuantityInsteadOfUpdateById() {
        stubAdjustableOrder();
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("5")));
        when(inventoryMapper.selectOne(any())).thenReturn(existingRow());

        service.applyAdjustment("st-1", "tester");

        verify(inventoryMapper).touchQuantity("inv-1", new BigDecimal("5"), "tester");
        verify(inventoryMapper, never()).updateById(any(WmsInventory.class));
    }

    /** 删除路径防回归：实盘 0 仍然删行，改造不改变盘亏清零行为。 */
    @Test
    void adjustZeroActualShouldStillDeleteRow() {
        stubAdjustableOrder();
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("0")));
        when(inventoryMapper.selectOne(any())).thenReturn(existingRow());

        service.applyAdjustment("st-1", "tester");

        verify(inventoryMapper).deleteById("inv-1");
    }

    /** 插入路径：盘盈新行的审计字段必须齐（WmsUtils.fillCreate 效果）。 */
    @Test
    void adjustInsertShouldFillAuditColumns() {
        stubAdjustableOrder();
        when(detailMapper.selectList(any())).thenReturn(List.of(detail("3")));
        // selectOne 未打桩 → 返回 null：库存行不存在，盘盈走插入

        service.applyAdjustment("st-1", "tester");

        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).insert(captor.capture());
        assertNotNull(captor.getValue().getCreateTime(), "create_time 应由 fillCreate 填入");
        assertNotNull(captor.getValue().getUpdateTime(), "update_time 应由 fillCreate 填入");
        assertNotNull(captor.getValue().getUpdateBy(), "update_by 不能为空：SB-001 对账依赖审计字段完整");
        assertEquals("tester", captor.getValue().getUpdateBy());
    }
}
