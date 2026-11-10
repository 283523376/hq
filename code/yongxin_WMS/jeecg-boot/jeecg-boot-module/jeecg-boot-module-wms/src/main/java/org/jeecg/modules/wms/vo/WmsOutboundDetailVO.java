package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 出库单详情（主表 + 需求明细 + 分配明细）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库单详情")
public class WmsOutboundDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "出库单")
    private WmsOutboundOrder order;

    @Schema(description = "需求明细")
    private List<WmsOutboundDetail> details = new ArrayList<>();

    @Schema(description = "分配明细")
    private List<WmsOutboundAllocation> allocations = new ArrayList<>();
}
