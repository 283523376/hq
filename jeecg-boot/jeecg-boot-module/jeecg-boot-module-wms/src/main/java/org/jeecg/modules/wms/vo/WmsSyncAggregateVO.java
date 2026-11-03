package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: SB-001 库存对账聚合行（spec 2026-11-03 §2.3）。
 *
 * <p>仓库 + 物料 + 批次 一行（对账粒度到仓库，库位/容器已在 SQL 里聚合掉）。
 * 仓库/物料各带 ERP 侧匹配键：{@code erpWarehouseCode} = COALESCE(erp_stock_code, warehouse_code)，
 * {@code erpMaterialCode} = COALESCE(kingdee_code, '')——空表示非 ERP 物料，快照里记「仅WMS」。
 * {@code lockedQty} 只附带展示，不参与数量比对（金蝶账上没有 WMS 锁定概念）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@Schema(description = "SB-001 库存对账聚合行")
public class WmsSyncAggregateVO {

    @Schema(description = "ERP仓库编码（COALESCE(erp_stock_code, warehouse_code)）")
    private String erpWarehouseCode;

    @Schema(description = "WMS仓库编码")
    private String warehouseCode;

    @Schema(description = "ERP物料编码（COALESCE(kingdee_code, '')，空=非ERP物料）")
    private String erpMaterialCode;

    @Schema(description = "WMS物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "批次号（空批归一为空串）")
    private String batchNo;

    @Schema(description = "库存数量合计")
    private BigDecimal qty;

    @Schema(description = "锁定数量合计（仅展示，不参与比对）")
    private BigDecimal lockedQty;
}
