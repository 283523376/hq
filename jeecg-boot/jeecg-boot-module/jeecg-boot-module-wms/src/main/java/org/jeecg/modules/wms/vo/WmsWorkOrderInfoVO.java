package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 扫码调出的工单信息（含已入库与剩余可入库数量）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "扫码工单信息")
public class WmsWorkOrderInfoVO {

    @Schema(description = "工单号")
    private String workOrderNo;

    @Schema(description = "物料ID")
    private String materialId;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "产品类型：半成品/成品")
    private String productType;

    @Schema(description = "计划数量")
    private BigDecimal planQty;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "工单状态")
    private String workOrderStatus;

    @Schema(description = "已占用数量（未关闭的生产入库记录合计）")
    private BigDecimal inboundedQty;

    @Schema(description = "剩余可入库数量")
    private BigDecimal remainingQty;

    @Schema(description = "应绑定的标识类型：半成品=RFID，成品=二维码")
    private String identType;
}
