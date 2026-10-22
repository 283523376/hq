package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.entity.WmsTransferTask;

import java.util.List;

/**
 * @Description: 调拨库存执行（CK-004）：锁定、扣减、迁库位、目标仓新增。
 *
 * <p>所有方法都依赖条件更新（影响 0 行即抛异常），由调用方事务回滚；不允许「先查后改」的裸写法。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
public interface IWmsTransferInventoryService {

    /**
     * 按源仓 FIFO 分配并锁定库存，返回分配行（每个库存行一条）。
     *
     * @param order  调拨单（取源仓/区域）
     * @param detail 明细（取物料/批次/容器/数量）
     * @return 分配行（已写入 wms_transfer_task，taskStatus=未下发）
     */
    List<WmsTransferTask> allocate(WmsTransferOrder order, WmsTransferDetail detail);

    /** 释放分配行的锁定（关单/异常处置），不清他单锁定。 */
    void release(WmsTransferTask task);

    /** 扣减源库存并释放锁定（跨仓出发、同仓实物完成）。 */
    void deductSource(WmsTransferTask task);

    /** 目标库位入库：同库位同物料同批次则累加，否则新建行。 */
    void addToLocation(WmsTransferTask task, WmsTransferDetail detail, String locationId, String locationCode);

    /** 目标库位出库（跨仓上架时从暂存库位移出）。 */
    void deductFromLocation(WmsTransferDetail detail, String locationId, java.math.BigDecimal qty);

    /** 占用库位（状态置「占用」，已占用视为正常）。 */
    void occupyLocation(String locationId);

    /** 释放库位（状态回「空闲」，故障库位不动）。 */
    void releaseLocation(String locationId);
}
