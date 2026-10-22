package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: 出库确认入参（CK-001 取料确认 / CK-002 确认出库）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库确认入参")
public class WmsOutboundConfirmDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "实收数量调整（可空）")
    private List<Item> details;

    @Data
    @Schema(description = "实收明细行")
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "分配明细ID")
        private String allocationId;

        @Schema(description = "实收数量")
        private BigDecimal actualQty;
    }
}
