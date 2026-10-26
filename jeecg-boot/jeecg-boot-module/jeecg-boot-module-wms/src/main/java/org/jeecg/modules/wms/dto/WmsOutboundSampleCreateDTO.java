package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 样品出库建单入参。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@Schema(description = "样品出库建单入参")
public class WmsOutboundSampleCreateDTO {

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "数量")
    private BigDecimal qty;

    @Schema(description = "批次号（可空）")
    private String batchNo;

    @Schema(description = "备注")
    private String remark;
}
