package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsStocktakePlan;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakePlanMapper;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点计划——周期滚动圈库（跳过冻结、循环取模）、到期执行（spec §5.3）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakePlanTest {

    private WmsStocktakePlanMapper planMapper;
    private WmsLocationMapper locationMapper;
    private IWmsStocktakeService stocktakeService;
    private WmsStocktakePlanServiceImpl service;

    @BeforeEach
    void setUp() {
        planMapper = mock(WmsStocktakePlanMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        stocktakeService = mock(IWmsStocktakeService.class);
        service = new WmsStocktakePlanServiceImpl(planMapper, locationMapper, stocktakeService);
    }

    private WmsStocktakePlan plan(String type, Integer rollingSize, boolean enabled) {
        WmsStocktakePlan plan = new WmsStocktakePlan();
        plan.setId("p1");
        plan.setPlanName("计划1");
        plan.setPlanType(type);
        plan.setRollingSize(rollingSize);
        plan.setEnabled(enabled ? "1" : "0");
        return plan;
    }

    @Test
    void cycleRollingShouldSkipFrozenAndWrapAround() {
        when(planMapper.selectList(any())).thenReturn(List.of(plan("周期", 3, true)));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                loc("L1", "0"), loc("L2", "1"), loc("L3", "0"),
                loc("L4", "0"), loc("L5", "0")));
        when(stocktakeService.create(any(), any())).thenReturn(new org.jeecg.modules.wms.entity.WmsStocktakeOrder());

        service.runDuePlans();

        ArgumentCaptor<org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO> captor =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO.class);
        verify(stocktakeService).create(captor.capture(), any());
        List<String> ids = captor.getValue().getLocationIds();
        assertEquals(3, ids.size());
        // L2 冻结被跳过：首期（无游标）从头取 3 个可用位
        assertEquals(List.of("L1", "L3", "L4"), ids);
        verify(planMapper).updateById(org.mockito.ArgumentMatchers.any(WmsStocktakePlan.class));
    }

    @Test
    void disabledPlanShouldBeIgnored() {
        when(planMapper.selectList(any())).thenReturn(List.of(plan("定期", null, false)));

        service.runDuePlans();

        verify(stocktakeService, never()).create(any(), any());
    }

    @Test
    void periodicPlanShouldCoverAllLocations() {
        when(planMapper.selectList(any())).thenReturn(List.of(plan("定期", null, true)));
        when(locationMapper.selectList(any())).thenReturn(List.of(loc("L1", "0"), loc("L2", "0")));

        service.runDuePlans();

        ArgumentCaptor<org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO> captor =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO.class);
        verify(stocktakeService).create(captor.capture(), any());
        assertEquals(2, captor.getValue().getLocationIds().size());
        assertEquals(WmsConstants.STOCKTAKE_TYPE_PERIODIC, captor.getValue().getStocktakeType());
    }

    private static WmsLocation loc(String id, String frozen) {
        WmsLocation location = new WmsLocation();
        location.setId(id);
        location.setLocationCode(id);
        location.setStocktakeFrozen(frozen);
        return location;
    }

    @Test
    void cycleRollingShouldResumeAfterCursorAndWrap() {
        WmsStocktakePlan plan = plan("周期", 3, true);
        plan.setLastCursor("L3"); // 上次圈到 L3
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        when(locationMapper.selectList(any())).thenReturn(List.of(
                loc("L1", "0"), loc("L2", "0"), loc("L3", "0"), loc("L4", "0")));
        when(stocktakeService.create(any(), any())).thenReturn(new org.jeecg.modules.wms.entity.WmsStocktakeOrder());

        service.runDuePlans();

        ArgumentCaptor<org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO> captor =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO.class);
        verify(stocktakeService).create(captor.capture(), any());
        // 从 L3 之后续接：L4 → 回卷 L1、L2（补满 rollingSize=3）
        assertEquals(List.of("L4", "L1", "L2"), captor.getValue().getLocationIds());
        assertEquals("L2", capturedCursor());
    }

    private String capturedCursor() {
        ArgumentCaptor<WmsStocktakePlan> captor = ArgumentCaptor.forClass(WmsStocktakePlan.class);
        verify(planMapper).updateById(captor.capture());
        return captor.getValue().getLastCursor();
    }
}
