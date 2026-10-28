package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 按物料汇总的可用库存（quantity - locked_qty），库存下限预警用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "物料可用库存汇总")
public class WmsMaterialAvailableVO {

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "可用量合计")
    private BigDecimal availableQty;
}
