package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 不良品入库登记入参（RK-005）：生产 / 受托两类来源统一入口。
 *
 * <p>物料只传 {@code materialId}，物料编码由服务端按主数据回填——防止入参里的
 * 编码与主数据不一致让台账对不上库存。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@Accessors(chain = true)
@Schema(description = "不良品入库登记入参")
public class WmsDefectInboundDTO {

    /** 来源类型：PRODUCTION 生产 / CONSIGNED 受托。 */
    @Schema(description = "来源类型：PRODUCTION 生产 / CONSIGNED 受托")
    private String sourceType;

    /** 来源单号（生产入库单号 / 受托入库单号）。受托来源必须等于入库单号，用于反查受托方。 */
    @Schema(description = "来源单号（生产入库单号 / 受托入库单号）")
    private String sourceBillNo;

    /** 物料ID（wms_material.id）。 */
    @Schema(description = "物料ID")
    private String materialId;

    /** 批次号。受托来源必填（扣减受托库存要按物料+批次+受托方匹配）。 */
    @Schema(description = "批次号")
    private String batchNo;

    /** 不良数量。 */
    @Schema(description = "不良数量")
    private BigDecimal qty;

    /** 不良原因。 */
    @Schema(description = "不良原因")
    private String reason;

    /** 处置方式：SCRAP 入废品仓 / RETURN 退货。 */
    @Schema(description = "处置方式：SCRAP 入废品仓 / RETURN 退货")
    private String disposalType;
}
