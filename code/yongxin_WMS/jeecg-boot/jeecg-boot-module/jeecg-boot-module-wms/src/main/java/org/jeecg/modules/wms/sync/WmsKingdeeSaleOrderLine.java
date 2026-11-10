package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * @Description: 金蝶销售订单分录（SAL_SaleOrder.FSaleOrderEntry）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "金蝶销售订单分录")
public class WmsKingdeeSaleOrderLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "物料编码")
    private String materialCode;

    @Schema(description = "物料名称")
    private String materialName;

    @Schema(description = "单位编码")
    private String unit;

    @Schema(description = "订单数量")
    private BigDecimal orderQty;

    @Schema(description = "要货日期")
    private Date deliveryDate;
}
