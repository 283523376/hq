package org.jeecg.modules.wms.service;

import org.jeecg.modules.wms.vo.WmsKingdeeStockSyncResultVO;

/**
 * @Description: 仓库档案 ERP 同步（BD_STOCK → wms_warehouse）与本地档案初始化。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
public interface IWmsKingdeeWarehouseSyncService {

    /**
     * 按白名单从金蝶拉取仓库并 upsert 到 WMS。
     *
     * <p>白名单为空时**不查询**、直接返回 0 行——避免把测试账套里约 190 个仓整表灌入。
     */
    WmsKingdeeStockSyncResultVO syncStock();

    /**
     * 初始化本地仓库档案（幂等）：LB/MJ 改名为「半成品仓/产成品仓」，不存在则新建 CJ/TC。
     *
     * <p>改名只动 {@code warehouse_name}，绝不改 {@code warehouse_code}——后者被库位、
     * 库存与设备绑定引用。
     */
    void initLocalArchives();
}
