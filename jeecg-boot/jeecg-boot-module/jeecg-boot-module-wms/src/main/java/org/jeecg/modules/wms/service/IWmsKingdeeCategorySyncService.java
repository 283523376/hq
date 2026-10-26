package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;

/**
 * @Description: 物料分组 → 物料分类映射同步（BD_MATERIAL → wms_material_category）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsKingdeeCategorySyncService {

    /**
     * 按 {@code category-map} 规则（格式「金蝶分组前缀=WMS 分类编码」）把命中的分组盖到分类行上。
     *
     * <p>未配置规则时直接空转——金蝶侧的分类来源字段尚未定义（外部依赖 E2），
     * 此时不给物料硬塞分类，避免产生脏数据。
     */
    WmsKingdeeStockSyncResultVO syncCategory();
}
