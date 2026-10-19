package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * @Description: MES 推送工单入参（占位契约，MES 侧确认后调整）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "MES 推送工单入参")
public class WmsWorkOrderPushDTO {

    @Schema(description = "工单号")
    private String workOrderNo;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "产品类型：半成品/成品")
    private String productType;

    @Schema(description = "计划数量")
    private BigDecimal planQty;

    @Schema(description = "批次号")
    private String batchNo;

    @Schema(description = "工单状态：待生产/生产中/已完成/已关闭")
    private String workOrderStatus;

    @Schema(description = "MES 侧单号")
    private String mesOrderNo;

    @Schema(description = "备注")
    private String remark;
}
