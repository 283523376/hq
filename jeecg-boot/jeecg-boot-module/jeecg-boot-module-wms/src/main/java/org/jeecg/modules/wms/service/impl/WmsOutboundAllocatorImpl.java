package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.service.IWmsOutboundAllocator;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * @Description: 出库 FIFO 分配：跨库存行拆分、条件锁定、库位占用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Slf4j
@Service
public class WmsOutboundAllocatorImpl implements IWmsOutboundAllocator {

    private static final int AVAILABLE_LIMIT = 1000;

    private final WmsInventoryMapper inventoryMapper;
    private final WmsOutboundAllocationMapper allocationMapper;
    private final WmsOutboundDetailMapper detailMapper;
    private final WmsLocationMapper locationMapper;

    public WmsOutboundAllocatorImpl(WmsInventoryMapper inventoryMapper,
                                    WmsOutboundAllocationMapper allocationMapper,
                                    WmsOutboundDetailMapper detailMapper,
                                    WmsLocationMapper locationMapper) {
        this.inventoryMapper = inventoryMapper;
        this.allocationMapper = allocationMapper;
        this.detailMapper = detailMapper;
        this.locationMapper = locationMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void allocate(WmsOutboundOrder order, List<WmsOutboundDetail> details) {
        boolean pick = WmsConstants.OUTBOUND_TYPE_PICK.equals(order.getOutboundType());
        String warehouseType = pick ? WmsConstants.WAREHOUSE_TYPE_STEREO : WmsConstants.WAREHOUSE_TYPE_AGILE;
        boolean requireContainer = !pick;

        for (WmsOutboundDetail detail : details) {
            BigDecimal required = nvl(detail.getRequiredQty());
            BigDecimal allocated = BigDecimal.ZERO;
            List<WmsInventoryAvailableVO> rows = inventoryMapper.selectAvailable(
                    detail.getMaterialId(), detail.getBatchNo(), warehouseType, requireContainer, AVAILABLE_LIMIT);
            if (rows != null) {
                for (WmsInventoryAvailableVO row : rows) {
                    if (allocated.compareTo(required) >= 0) {
                        break;
                    }
                    BigDecimal available = nvl(row.getQuantity()).subtract(nvl(row.getLockedQty()));
                    if (available.signum() <= 0) {
                        continue;
                    }
                    // 盘点冻结的库位不可分配（否则盘出来的差异会被并发出库搅乱）
                    WmsStocktakeFreezeGuard.assertNotFrozen(row.getLocationId(), locationMapper);
                    BigDecimal take = required.subtract(allocated).min(available);
                    if (inventoryMapper.lockInventory(row.getId(), take) == 0) {
                        throw new RuntimeException("库存已被其他出库单占用，请重试");
                    }
                    allocated = allocated.add(take);

                    WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                            .setOutboundId(order.getId())
                            .setDetailId(detail.getId())
                            .setInventoryId(row.getId())
                            .setLocationId(row.getLocationId())
                            .setLocationCode(row.getLocationCode())
                            .setContainerCode(row.getContainerCode() == null ? "" : row.getContainerCode())
                            .setAllocatedQty(take)
                            .setActualQty(BigDecimal.ZERO)
                            .setTaskNo(nextTaskNo(pick))
                            .setTaskStatus(WmsConstants.TASK_STATUS_NOT_DISPATCHED)
                            .setReviewStatus(WmsConstants.REVIEW_STATUS_PENDING);
                    allocationMapper.insert(allocation);

                    if (pick) {
                        occupyLocation(row.getLocationId());
                    }
                }
            }
            if (allocated.compareTo(required) < 0) {
                throw new RuntimeException("库存不足：" + detail.getMaterialCode()
                        + " 需求 " + strip(required) + "，可用 " + strip(allocated));
            }
            detail.setAllocatedQty(allocated);
            detail.setActualQty(allocated);
            detail.setUpdateTime(new Date());
            detailMapper.updateById(detail);
        }
    }

    /** 半成品立库：分配即占用库位；已占用视为正常（库存锁定已保证不超发）。 */
    private void occupyLocation(String locationId) {
        if (locationId == null) {
            return;
        }
        locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                .set("status", WmsConstants.LOCATION_STATUS_OCCUPIED)
                .set("update_time", new Date())
                .eq("id", locationId)
                .eq("status", WmsConstants.LOCATION_STATUS_IDLE));
    }

    private String nextTaskNo(boolean pick) {
        return (pick ? WmsConstants.TASK_NO_PREFIX_WCS : WmsConstants.TASK_NO_PREFIX_RMS)
                + IdWorker.getIdStr();
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
