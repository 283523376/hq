package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * @Description: 报废出库入参（XT-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Accessors(chain = true)
@Schema(description = "报废出库入参")
public class WmsScrapDTO {

    @Schema(description = "库存行 id（不良品台账的行）")
    private String inventoryId;

    @Schema(description = "报废数量")
    private BigDecimal quantity;

    @Schema(description = "报废原因")
    private String reason;
}
