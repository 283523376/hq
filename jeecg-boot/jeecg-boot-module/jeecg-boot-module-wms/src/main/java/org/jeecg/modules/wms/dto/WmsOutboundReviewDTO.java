package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: CK-002 出库复核入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库复核入参")
public class WmsOutboundReviewDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "复核明细")
    private List<Item> details;

    @Schema(description = "是否放行不一致项")
    private boolean releaseMismatch;

    @Schema(description = "放行原因（releaseMismatch=true 时必填）")
    private String releaseReason;

    @Data
    @Schema(description = "复核明细行")
    public static class Item implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "分配明细ID")
        private String allocationId;

        @Schema(description = "扫描码（物料编码或料箱编码）")
        private String scanCode;

        @Schema(description = "实收数量（可空，默认分配数量）")
        private BigDecimal actualQty;
    }
}
