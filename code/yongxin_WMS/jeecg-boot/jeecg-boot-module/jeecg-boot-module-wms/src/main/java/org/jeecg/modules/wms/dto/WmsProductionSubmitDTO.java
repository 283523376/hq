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

    @Schema(description = "产品类型（可选）：为空时取工单产品类型；改制单完成传「半成品」→ 立库")
    private String productType;

    @Schema(description = "备注（可选，写入入库单 remark 列；改制单完成传「改制单:单号」溯源）")
    private String remark;

    /** 技术债 §5-23：改制单产出用结构化关联，不再只靠 remark 字符串匹配。 */
    @Schema(description = "改制单ID（改制完成时传，写入入库单 conversion_order_id）")
    private String conversionOrderId;

    @Schema(description = "来源物料编码（改制投入物料，写入入库单 source_material_code）")
    private String sourceMaterialCode;
}
