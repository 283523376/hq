package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;

/**
 * 物料主数据同步（金蝶 BD_MATERIAL → wms_material，恒等映射）。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
public interface IWmsKingdeeMaterialSyncService {

    /** 拉取金蝶物料并 upsert 到 wms_material（编码=金蝶 FNumber，kingdee_code 恒等）。 */
    WmsKingdeeStockSyncResultVO syncMaterial();
}
