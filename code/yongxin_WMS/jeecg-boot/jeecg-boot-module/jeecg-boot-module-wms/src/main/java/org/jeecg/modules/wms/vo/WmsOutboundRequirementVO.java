package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * @Description: CK-001 领料需求行（来源：金蝶 PRD_PPBOM 或手工）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "领料需求行")
public class WmsOutboundRequirementVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "WMS 物料编码；未匹配时为金蝶编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "需求数量（已扣减已领数量）")
    private BigDecimal requiredQty;

    @Schema(description = "单位")
    private String unit;

    @Schema(description = "金蝶 PPBOM 子项来源内码")
    private String sourceEntryId;

    @Schema(description = "是否已匹配到 WMS 物料")
    private Boolean matched;
}
