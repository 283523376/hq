package org.jeecg.modules.wms.sync;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶即时库存（STK_Inventory）读取，供 SB-001 上位系统对账使用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
public interface IWmsKingdeeInventoryReader {

    /**
     * 查询金蝶即时库存，按「仓库|物料|批号」汇总数量。
     *
     * @param warehouseCodes 仓库编码（金蝶 {@code FStockId.FNumber}）；null 或空 = 只按组织过滤
     * @return key = {@code 仓库编码|物料编码|批号}（批号 null/空串归一为 {@code ""}），
     *         value = 该键 {@code FBaseQty} 合计
     * @throws RuntimeException 金蝶查询失败（重试由调用方负责，Reader 一次一调）
     */
    Map<String, BigDecimal> queryInventory(List<String> warehouseCodes);
}
