package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: CK-001 扫码工单结果（工单信息 + 需求明细）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "扫码工单结果")
public class WmsOutboundScanVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "工单号")
    private String workOrderNo;

    @Schema(description = "产品物料编码")
    private String materialCode;

    @Schema(description = "产品物料名称")
    private String materialName;

    @Schema(description = "产品类型")
    private String productType;

    @Schema(description = "计划数量")
    private BigDecimal planQty;

    @Schema(description = "工单批次")
    private String batchNo;

    @Schema(description = "工单状态")
    private String workOrderStatus;

    @Schema(description = "需求来源：金蝶用料清单/手工")
    private String requirementSource;

    @Schema(description = "提示信息（如需要手工录入）")
    private String warning;

    @Schema(description = "需求明细")
    private List<WmsOutboundRequirementVO> details = new ArrayList<>();
}
