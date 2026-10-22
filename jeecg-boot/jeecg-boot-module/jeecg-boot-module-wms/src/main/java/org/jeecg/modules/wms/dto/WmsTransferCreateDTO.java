package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: 新建调拨单入参（CK-004）。
 *
 * <p>单据只到「仓库/区域」级；同仓区域搬运必须给调入区域，跨仓在途暂存库位由后端配置补齐。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "新建调拨单入参")
public class WmsTransferCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "调出库区ID")
    @NotBlank(message = "调出库区不能为空")
    private String outWarehouseId;

    @Schema(description = "调出区域编码（可空）")
    private String outZoneCode;

    @Schema(description = "调入库区ID")
    @NotBlank(message = "调入库区不能为空")
    private String inWarehouseId;

    @Schema(description = "调入区域编码（同仓区域搬运必填）")
    private String inZoneCode;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "调拨明细")
    @NotEmpty(message = "调拨明细不能为空")
    @Valid
    private List<Detail> details;

    /**
     * 明细行。
     */
    @Data
    @Schema(description = "调拨明细行")
    public static class Detail implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "物料ID")
        @NotBlank(message = "物料不能为空")
        private String materialId;

        @Schema(description = "批次号（空=执行时按 FIFO 取批次）")
        private String batchNo;

        @Schema(description = "料箱/托盘编码（敏捷仓必填）")
        private String containerCode;

        @Schema(description = "单位")
        private String unit;

        @Schema(description = "调拨数量")
        @NotNull(message = "调拨数量不能为空")
        @DecimalMin(value = "0.001", message = "调拨数量必须大于 0")
        private BigDecimal transferQty;

        @Schema(description = "备注")
        private String remark;
    }
}
