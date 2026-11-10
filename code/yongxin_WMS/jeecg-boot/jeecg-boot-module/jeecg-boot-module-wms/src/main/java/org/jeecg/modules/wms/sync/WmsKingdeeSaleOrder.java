package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @Description: 金蝶销售订单（表头 + 分录），CK-002 方向反转后的只读模型。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "金蝶销售订单")
public class WmsKingdeeSaleOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "销售订单号")
    private String salesOrderNo;

    @Schema(description = "金蝶单据状态 A/B/C/D/Z")
    private String documentStatus;

    @Schema(description = "订单日期")
    private Date orderDate;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "销售组织编码")
    private String saleOrgNumber;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "分录")
    private List<WmsKingdeeSaleOrderLine> lines = new ArrayList<>();
}
