package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.entity.WmsTransferTask;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsTransferTaskMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsTransferInventoryService;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 调拨库存执行实现（CK-004）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>锁定/扣减全部条件更新，0 行即抛异常（防超发、防清别单锁定）；</li>
 *   <li>分配按「源仓」取数（{@code selectAvailableByWarehouse}），不按仓库类型取，避免同类型多仓混用；</li>
 *   <li>敏捷仓（有料箱）按容器维度分配，立体库按库位维度。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Slf4j
@Service
public class WmsTransferInventoryServiceImpl implements IWmsTransferInventoryService {

    private static final int AVAILABLE_LIMIT = 1000;

    private final WmsInventoryMapper inventoryMapper;
    private final WmsLocationMapper locationMapper;
    private final WmsWarehouseMapper warehouseMapper;
    private final WmsTransferTaskMapper taskMapper;

    public WmsTransferInventoryServiceImpl(WmsInventoryMapper inventoryMapper,
                                           WmsLocationMapper locationMapper,
                                           WmsWarehouseMapper warehouseMapper,
                                           WmsTransferTaskMapper taskMapper) {
        this.inventoryMapper = inventoryMapper;
        this.locationMapper = locationMapper;
        this.warehouseMapper = warehouseMapper;
        this.taskMapper = taskMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<WmsTransferTask> allocate(WmsTransferOrder order, WmsTransferDetail detail) {
        String outWarehouseId = order.getOutWarehouseId();
        boolean requireContainer = isAgile(order.getOutWarehouseId());
        BigDecimal required = nvl(detail.getTransferQty());
        List<WmsInventoryAvailableVO> rows = inventoryMapper.selectAvailableByWarehouse(
                detail.getMaterialId(), detail.getBatchNo(), outWarehouseId,
                requireContainer ? detail.getContainerCode() : null, requireContainer, AVAILABLE_LIMIT);
        List<WmsTransferTask> tasks = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        if (rows != null) {
            for (WmsInventoryAvailableVO row : rows) {
                if (allocated.compareTo(required) >= 0) {
                    break;
                }
                // 受托库存不参与自有移库（spec 2026-11-04 §3.1）：SQL 已默认过滤，服务层再兜一道
                if (!WmsConstants.OWNERSHIP_OWNED.equals(row.getOwnershipType())) {
                    continue;
                }
                // 盘点冻结的源库位不可建移库任务：否则盘点差异被并发移库搅乱、产出假盘亏
                WmsStocktakeFreezeGuard.assertNotFrozen(row.getLocationId(), locationMapper);
                BigDecimal available = nvl(row.getQuantity()).subtract(nvl(row.getLockedQty()));
                if (available.signum() <= 0) {
                    continue;
                }
                BigDecimal take = required.subtract(allocated).min(available);
                if (inventoryMapper.lockInventory(row.getId(), take) == 0) {
                    throw new RuntimeException("库存已被其他单据占用，请重试");
                }
                WmsTransferTask task = new WmsTransferTask()
                        .setTransferId(order.getId())
                        .setDetailId(detail.getId())
                        .setInventoryId(row.getId())
                        .setAllocatedQty(take)
                        .setContainerCode(row.getContainerCode() == null ? "" : row.getContainerCode())
                        .setZoneCode(order.getInZoneCode())
                        .setTaskNo(WmsConstants.TASK_NO_PREFIX_RMS + IdWorker.getIdStr())
                        .setTaskStatus(WmsConstants.TASK_STATUS_NOT_DISPATCHED)
                        .setMocked(Boolean.FALSE);
                taskMapper.insert(task);
                tasks.add(task);
                allocated = allocated.add(take);
                if (detail.getOutLocationId() == null) {
                    detail.setOutLocationId(row.getLocationId());
                    detail.setOutLocationCode(row.getLocationCode());
                }
            }
        }
        if (allocated.compareTo(required) < 0) {
            throw new RuntimeException("库存不足：" + detail.getMaterialCode()
                    + " 需求 " + strip(required) + "，可用 " + strip(allocated));
        }
        return tasks;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(WmsTransferTask task) {
        if (task == null || task.getInventoryId() == null || nvl(task.getAllocatedQty()).signum() <= 0) {
            return;
        }
        int affected = inventoryMapper.releaseLocked(task.getInventoryId(), nvl(task.getAllocatedQty()));
        if (affected == 0) {
            log.warn("释放调拨锁定失败（可能已扣减）：task={} inventory={}", task.getTaskNo(), task.getInventoryId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductSource(WmsTransferTask task) {
        BigDecimal qty = nvl(task.getAllocatedQty());
        int affected = inventoryMapper.deductLocked(task.getInventoryId(), qty);
        if (affected == 0) {
            throw new RuntimeException("库存扣减失败：库存行已被其它单据占用或数量不足");
        }
        WmsInventory row = inventoryMapper.selectById(task.getInventoryId());
        if (row != null) {
            releaseLocation(row.getLocationId());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addToLocation(WmsTransferTask task, WmsTransferDetail detail, String locationId, String locationCode) {
        if (locationId == null || locationId.isEmpty()) {
            throw new RuntimeException("目标库位不能为空");
        }
        WmsStocktakeFreezeGuard.assertNotFrozen(locationId, locationMapper);
        BigDecimal qty = nvl(task.getAllocatedQty());
        String batchNo = detail.getBatchNo() == null ? "" : detail.getBatchNo();
        String containerCode = detail.getContainerCode() == null ? "" : detail.getContainerCode();
        WmsInventory exist = findRow(detail.getMaterialId(), locationId, batchNo, containerCode);
        if (exist != null) {
            inventoryMapper.update(null, new UpdateWrapper<WmsInventory>()
                    .setSql("quantity = quantity + " + strip(qty))
                    .set("update_time", new Date())
                    .eq("id", exist.getId()));
        } else {
            // 移库不是新入库：目标行继承源行的批次首次入库时间，否则超期预警的货龄会被重置
            WmsInventory sourceRow = task.getInventoryId() == null
                    ? null : inventoryMapper.selectById(task.getInventoryId());
            WmsInventory created = new WmsInventory()
                    .setMaterialId(detail.getMaterialId())
                    .setLocationId(locationId)
                    .setBatchNo(batchNo)
                    .setContainerCode(containerCode)
                    .setQuantity(qty)
                    .setLockedQty(BigDecimal.ZERO)
                    .setFirstInboundTime(sourceRow == null ? null : sourceRow.getFirstInboundTime());
            created.setId(IdWorker.getIdStr());
            inventoryMapper.upsertInventory(created);
        }
        occupyLocation(locationId);
        detail.setInLocationId(locationId);
        detail.setInLocationCode(locationCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductFromLocation(WmsTransferDetail detail, String locationId, BigDecimal qty) {
        String batchNo = detail.getBatchNo() == null ? "" : detail.getBatchNo();
        String containerCode = detail.getContainerCode() == null ? "" : detail.getContainerCode();
        WmsInventory exist = findRow(detail.getMaterialId(), locationId, batchNo, containerCode);
        if (exist == null || nvl(exist.getQuantity()).compareTo(nvl(qty)) < 0) {
            throw new RuntimeException("暂存库位库存不足，无法上架：" + detail.getMaterialCode());
        }
        inventoryMapper.update(null, new UpdateWrapper<WmsInventory>()
                .setSql("quantity = quantity - " + strip(qty))
                .set("update_time", new Date())
                .eq("id", exist.getId())
                .ge("quantity", qty));
        // 暂存库位被清空后回「空闲」，避免上架完成后库位长期显示占用
        WmsInventory after = inventoryMapper.selectById(exist.getId());
        if (after != null && nvl(after.getQuantity()).signum() == 0) {
            releaseLocation(locationId);
        }
    }

    @Override
    public void occupyLocation(String locationId) {
        if (locationId == null) {
            return;
        }
        locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                .set("status", WmsConstants.LOCATION_STATUS_OCCUPIED)
                .set("update_time", new Date())
                .eq("id", locationId)
                .eq("status", WmsConstants.LOCATION_STATUS_IDLE));
    }

    @Override
    public void releaseLocation(String locationId) {
        if (locationId == null) {
            return;
        }
        locationMapper.update(null, new UpdateWrapper<WmsLocation>()
                .set("status", WmsConstants.LOCATION_STATUS_IDLE)
                .set("update_time", new Date())
                .eq("id", locationId)
                .eq("status", WmsConstants.LOCATION_STATUS_OCCUPIED));
    }

    /** 该库区是否敏捷仓（按容器记库存）。 */
    private boolean isAgile(String warehouseId) {
        if (warehouseId == null) {
            return false;
        }
        var warehouse = warehouseMapper.selectById(warehouseId);
        return warehouse != null && WmsConstants.WAREHOUSE_TYPE_AGILE.equals(warehouse.getWarehouseType());
    }

    private WmsInventory findRow(String materialId, String locationId, String batchNo, String containerCode) {
        // 移库只处理自有库存：六列唯一键允许同库位存在同一物料/批次/容器的受托行，
        // 不带 ownership_type 有可能把自有数量加到受托行上（spec 2026-11-04 §3.1）
        List<WmsInventory> rows = inventoryMapper.selectList(new LambdaQueryWrapper<WmsInventory>()
                .eq(WmsInventory::getMaterialId, materialId)
                .eq(WmsInventory::getLocationId, locationId)
                .eq(WmsInventory::getBatchNo, batchNo)
                .eq(WmsInventory::getContainerCode, containerCode)
                .eq(WmsInventory::getOwnershipType, WmsConstants.OWNERSHIP_OWNED)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String strip(BigDecimal value) {
        return nvl(value).stripTrailingZeros().toPlainString();
    }
}
