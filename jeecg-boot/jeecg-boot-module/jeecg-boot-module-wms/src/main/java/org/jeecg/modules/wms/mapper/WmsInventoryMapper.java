package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {

    /**
     * 聚合上架：同 (material_id, location_id, batch_no, container_code) 存在则数量累加，否则插入新行。
     *
     * @return 影响行数（插入=1，累加=2）
     */
    int upsertInventory(WmsInventory inv);

    /**
     * 出库可用库存（按 FIFO 排序）：quantity - locked_qty &gt; 0。
     *
     * @param warehouseType   立库/敏捷仓
     * @param requireContainer 是否只取已绑定料箱的行（敏捷仓）
     */
    List<WmsInventoryAvailableVO> selectAvailable(@Param("materialId") String materialId,
                                                  @Param("batchNo") String batchNo,
                                                  @Param("warehouseType") String warehouseType,
                                                  @Param("requireContainer") boolean requireContainer,
                                                  @Param("limit") int limit);

    /** 锁定库存：可用量不足时影响 0 行。 */
    int lockInventory(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 指定库区的可用库存（FIFO）：调拨按「源仓」取数，避免同类型多仓混用。
     *
     * @param warehouseId      源库区ID
     * @param requireContainer 是否只取已绑定料箱的行（敏捷仓）
     */
    List<WmsInventoryAvailableVO> selectAvailableByWarehouse(@Param("materialId") String materialId,
                                                         @Param("batchNo") String batchNo,
                                                         @Param("warehouseId") String warehouseId,
                                                         @Param("containerCode") String containerCode,
                                                             @Param("requireContainer") boolean requireContainer,
                                                             @Param("limit") int limit);

    /** 释放锁定（关单/复核差额）。 */
    int releaseLocked(@Param("id") String id, @Param("qty") BigDecimal qty);

    /** 扣减库存并释放锁定：锁定不足或库存不足时影响 0 行。 */
    int deductLocked(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 直接扣减库存（不涉及锁定）——报废出库用。
     *
     * @return 影响行数；0 表示库存已被并发改动或不足，调用方应回滚
     */
    int deductQuantity(@Param("id") String id, @Param("qty") BigDecimal qty);

    /**
     * 整行覆盖库存数量并显式刷新 update_time / update_by——盘点调整用（SB-001 前置修复）。
     *
     * <p>「今日变动」对账按 {@code update_time} 取数；本项目无 MyBatis-Plus 自动填充，
     * 走通用 {@code updateById} 审计列不会变，对账会漏掉全部盘点调整。
     * 盘点是覆盖不是增减，故不带数量余量条件。
     */
    int touchQuantity(@Param("id") String id, @Param("qty") BigDecimal qty, @Param("updateBy") String updateBy);

    /**
     * 库存台账分页（KC-004）。
     *
     * <p>排序按 `wms_inventory` 唯一键的四列，与 {@link #selectLedgerCursor} 保持一致，
     * 这样页面看到的顺序和导出文件里的一致。
     */
    IPage<WmsInventoryLedgerVO> selectLedgerPage(Page<WmsInventoryLedgerVO> page,
                                                 @Param("q") WmsInventoryLedgerQueryDTO q);

    /**
     * 库存台账游标分批（导出专用）。
     *
     * <p>`lastXxx` 全部为 null 时取第一批；否则取严格大于该四元组的下一批。
     * 四列是唯一键 `uk_wms_inv_mat_loc_batch_container`，所以游标不会重复或遗漏。
     */
    List<WmsInventoryLedgerVO> selectLedgerCursor(@Param("q") WmsInventoryLedgerQueryDTO q,
                                                  @Param("lastMaterialId") String lastMaterialId,
                                                  @Param("lastLocationId") String lastLocationId,
                                                  @Param("lastBatchNo") String lastBatchNo,
                                                  @Param("lastContainerCode") String lastContainerCode,
                                                  @Param("limit") int limit);
}
