package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * @Description: 库位属性更新入参（承重 / 状态）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "库位属性更新入参")
public class WmsLocationAttrDTO {

    @Schema(description = "库位ID")
    private String id;

    @Schema(description = "承重")
    private BigDecimal maxWeight;

    @Schema(description = "状态：空闲/占用/故障；不传则不改")
    private String status;
}