package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 盘点明细实盘录入项。
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@Schema(description = "盘点明细实盘录入项")
public class WmsStocktakeDetailVO {

    @Schema(description = "明细ID")
    private String id;

    @Schema(description = "实盘数量")
    private BigDecimal actualQty;

    @Schema(description = "差异原因")
    private String diffReason;
}
