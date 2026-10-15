package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 库位批量生成入参（含闭区间的排/列/层范围）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "库位批量生成入参")
public class WmsLocationGenDTO {

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "起始排")
    private Integer rowFrom;

    @Schema(description = "结束排（含）")
    private Integer rowTo;

    @Schema(description = "起始列")
    private Integer colFrom;

    @Schema(description = "结束列（含）")
    private Integer colTo;

    @Schema(description = "起始层")
    private Integer layerFrom;

    @Schema(description = "结束层（含）")
    private Integer layerTo;
}