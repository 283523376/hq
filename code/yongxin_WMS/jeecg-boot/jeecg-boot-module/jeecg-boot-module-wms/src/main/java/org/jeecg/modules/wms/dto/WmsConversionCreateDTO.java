package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 改制单（大改小）建单入参。
 *
 * <p>大改小 = 原材料加工成半成品：ERP 生产订单先行（WMS 只同步），WMS 建改制单
 * 登记投入与产出；投入原材料<strong>不入 WMS 库存</strong>，产出在完成时走生产入库通道。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@Schema(description = "改制单建单入参")
public class WmsConversionCreateDTO {

    @Schema(description = "生产订单号（必须已同步到 WMS 且已下达）")
    private String productionOrderNo;

    @Schema(description = "投入物料编码")
    private String inputMaterialCode;

    @Schema(description = "投入批次号（可空）")
    private String inputBatchNo;

    @Schema(description = "投入数量（>0，最多 3 位小数）")
    private BigDecimal inputQty;

    @Schema(description = "产出物料编码（必须与生产订单成品一致）")
    private String outputMaterialCode;

    @Schema(description = "产出批次号（为空时入库取生产订单批次）")
    private String outputBatchNo;

    @Schema(description = "产出数量（>0，最多 3 位小数，不得超过生产订单计划量）")
    private BigDecimal outputQty;
}
