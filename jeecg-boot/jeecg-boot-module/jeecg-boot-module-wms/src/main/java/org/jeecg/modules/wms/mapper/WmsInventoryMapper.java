package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;

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

    /** 释放锁定（关单/复核差额）。 */
    int releaseLocked(@Param("id") String id, @Param("qty") BigDecimal qty);

    /** 扣减库存并释放锁定：锁定不足或库存不足时影响 0 行。 */
    int deductLocked(@Param("id") String id, @Param("qty") BigDecimal qty);
}
