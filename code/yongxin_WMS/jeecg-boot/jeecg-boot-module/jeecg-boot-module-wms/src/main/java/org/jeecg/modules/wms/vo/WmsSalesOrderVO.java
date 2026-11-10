package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.wms.entity.WmsSalesOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrderDetail;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: 销售订单预览（表头 + 分录 + 剩余可出数量）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "销售订单预览")
public class WmsSalesOrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "销售订单表头")
    private WmsSalesOrder order;

    @Schema(description = "分录（含已出库数量）")
    private List<Line> lines;

    @Data
    @Schema(description = "销售订单分录（含剩余可出数量）")
    public static class Line implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "分录ID")
        private String id;

        @Schema(description = "行号")
        private Integer lineNo;

        @Schema(description = "WMS 物料ID（空=未建档）")
        private String materialId;

        @Schema(description = "物料编码")
        private String materialCode;

        @Schema(description = "物料名称")
        private String materialName;

        @Schema(description = "单位")
        private String unit;

        @Schema(description = "订单数量")
        private BigDecimal orderQty;

        @Schema(description = "已出库数量")
        private BigDecimal shippedQty;

        @Schema(description = "剩余可出数量 = 订单数量 - 已出库数量")
        private BigDecimal remainingQty;

        public static Line of(WmsSalesOrderDetail detail) {
            Line line = new Line();
            line.setId(detail.getId());
            line.setLineNo(detail.getLineNo());
            line.setMaterialId(detail.getMaterialId());
            line.setMaterialCode(detail.getMaterialCode());
            line.setMaterialName(detail.getMaterialName());
            line.setUnit(detail.getUnit());
            line.setOrderQty(detail.getOrderQty());
            BigDecimal shipped = detail.getShippedQty() == null ? BigDecimal.ZERO : detail.getShippedQty();
            line.setShippedQty(shipped);
            BigDecimal orderQty = detail.getOrderQty() == null ? BigDecimal.ZERO : detail.getOrderQty();
            line.setRemainingQty(orderQty.subtract(shipped).max(BigDecimal.ZERO));
            return line;
        }
    }
}
