package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsStocktakePlan;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakePlanMapper;
import org.jeecg.modules.wms.service.IWmsStocktakePlanService;
import org.jeecg.modules.wms.service.IWmsStocktakeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 盘点计划执行。
 *
 * <p>周期滚动：按 {@code location_code} 升序取全部**未冻结**库位，从上次游标
 * （{@code last_run_time} 之前圈过的数量，简化为每次从 0 顺延）向后取
 * {@code rollingSize} 个，到末尾回卷。首期实现不做 ABC 分级（spec D3）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Slf4j
@Service
public class WmsStocktakePlanServiceImpl implements IWmsStocktakePlanService {

    private final WmsStocktakePlanMapper planMapper;
    private final WmsLocationMapper locationMapper;
    private final IWmsStocktakeService stocktakeService;

    public WmsStocktakePlanServiceImpl(WmsStocktakePlanMapper planMapper,
                                       WmsLocationMapper locationMapper,
                                       IWmsStocktakeService stocktakeService) {
        this.planMapper = planMapper;
        this.locationMapper = locationMapper;
        this.stocktakeService = stocktakeService;
    }

    @Override
    public void runDuePlans() {
        List<WmsStocktakePlan> plans = planMapper.selectList(new LambdaQueryWrapper<WmsStocktakePlan>()
                .eq(WmsStocktakePlan::getEnabled, "1"));
        for (WmsStocktakePlan plan : plans) {
            try {
                runPlan(plan);
            } catch (Exception e) {
                // 单个计划失败不阻断其余计划（照既有补偿任务口径）
                log.error("盘点计划[{}]执行失败：{}", plan.getPlanName(), e.getMessage(), e);
            }
        }
    }

    private void runPlan(WmsStocktakePlan plan) {
        List<WmsLocation> locations = locationMapper.selectList(new LambdaQueryWrapper<WmsLocation>()
                .eq(WmsLocation::getStocktakeFrozen, WmsConstants.STOCKTAKE_UNFROZEN)
                .orderByAsc(WmsLocation::getLocationCode));
        if (locations.isEmpty()) {
            log.info("盘点计划[{}]无可盘库位（全部冻结或不存在），跳过", plan.getPlanName());
            return;
        }
        List<String> locationIds;
        if (WmsConstants.STOCKTAKE_TYPE_CYCLE.equals(plan.getPlanType())) {
            locationIds = rollingSlice(locations, plan.getRollingSize(), plan.getLastCursor());
            String lastTaken = locationIds.get(locationIds.size() - 1);
            String lastCode = locations.stream().filter(l -> lastTaken.equals(l.getId()))
                    .map(WmsLocation::getLocationCode).findFirst().orElse(null);
            plan.setLastCursor(lastCode);
        } else {
            locationIds = locations.stream().map(WmsLocation::getId).toList();
        }

        WmsStocktakeCreateDTO dto = new WmsStocktakeCreateDTO();
        dto.setStocktakeType(plan.getPlanType());
        dto.setLocationIds(locationIds);
        dto.setRemark("盘点计划[" + plan.getPlanName() + "]自动生成");
        stocktakeService.create(dto, "SYSTEM");

        plan.setLastRunTime(new Date());
        planMapper.updateById(plan);
        log.info("盘点计划[{}]已执行，圈定库位 {} 个", plan.getPlanName(), locationIds.size());
    }

    /**
     * 滚动圈定：Java 层再过滤一次冻结（SQL 过滤对单测不可见），从游标之后续接，到末尾回卷到头。
     *
     * @param lastCursor 上次圈到的库位编码；空 = 从头开始
     */
    static List<String> rollingSlice(List<WmsLocation> locations, Integer rollingSize, String lastCursor) {
        List<WmsLocation> available = locations.stream()
                .filter(l -> !WmsConstants.STOCKTAKE_FROZEN.equals(l.getStocktakeFrozen()))
                .toList();
        int size = rollingSize == null || rollingSize <= 0 ? available.size() : Math.min(rollingSize, available.size());
        int start = 0;
        if (lastCursor != null && !lastCursor.isEmpty()) {
            for (int i = 0; i < available.size(); i++) {
                if (lastCursor.equals(available.get(i).getLocationCode())) {
                    start = (i + 1) % available.size();
                    break;
                }
            }
        }
        List<String> ids = new ArrayList<>(size);
        for (int k = 0; k < available.size() && ids.size() < size; k++) {
            ids.add(available.get((start + k) % available.size()).getId());
        }
        return ids;
    }
}
