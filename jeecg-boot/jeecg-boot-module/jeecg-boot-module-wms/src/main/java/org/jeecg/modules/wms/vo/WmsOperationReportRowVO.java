package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 金蝶工序汇报单（SFC_OperationReport）分录行。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "工序汇报分录行")
public class WmsOperationReportRowVO {

    @Schema(description = "工序汇报单号（GXHB*）")
    private String billNo;

    @Schema(description = "单据日期")
    private String billDate;

    @Schema(description = "加工组织编码")
    private String orgNumber;

    @Schema(description = "加工车间名称")
    private String workshopName;

    @Schema(description = "生产订单号（= WMS 工单号）")
    private String moNumber;

    @Schema(description = "产出物料编码")
    private String materialCode;

    @Schema(description = "产出物料名称")
    private String materialName;

    @Schema(description = "批次号")
    private String lot;

    @Schema(description = "合格数量")
    private BigDecimal qualifiedQty;

    @Schema(description = "工序序列")
    private String seq;

    @Schema(description = "工序号")
    private String operNumber;

    @Schema(description = "入库仓库编码（可空 = 只汇报未入库）")
    private String stockNumber;

    @Schema(description = "入库仓库名称")
    private String stockName;
}
