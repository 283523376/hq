package org.jeecg.modules.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.jeecg.modules.wms.entity.WmsInventory;

/**
 * 库存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
public interface WmsInventoryMapper extends BaseMapper<WmsInventory> {

    /**
     * 聚合上架：同 (material_id, location_id, batch_no) 存在则数量累加，否则插入新行。
     *
     * @return 影响行数（插入=1，累加=2）
     */
    int upsertInventory(WmsInventory inv);
}
