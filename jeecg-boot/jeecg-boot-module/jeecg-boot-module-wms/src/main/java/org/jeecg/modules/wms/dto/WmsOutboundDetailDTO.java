package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @Description: 出库需求明细入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库需求明细入参")
public class WmsOutboundDetailDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "批次号（可空）")
    private String batchNo;

    @Schema(description = "需求数量")
    private BigDecimal requiredQty;

    @Schema(description = "单位（可空，默认取物料单位）")
    private String unit;

    @Schema(description = "金蝶 PPBOM 子项来源内码")
    private String sourceEntryId;
}
