package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: 生产入库提交入参（一次提交 = 一个载具）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "生产入库提交入参")
public class WmsProductionSubmitDTO {

    @Schema(description = "工单号")
    private String workOrderNo;

    @Schema(description = "良品数量")
    private BigDecimal goodQty;

    @Schema(description = "不良品数量")
    private BigDecimal badQty;

    @Schema(description = "载具码：成品=料箱二维码内容，半成品=周转盘 RFID 号")
    private String carrierCode;

    @Schema(description = "批次号（为空时取工单批次）")
    private String batchNo;

    @Schema(description = "不良原因")
    private String defectRemark;
}
