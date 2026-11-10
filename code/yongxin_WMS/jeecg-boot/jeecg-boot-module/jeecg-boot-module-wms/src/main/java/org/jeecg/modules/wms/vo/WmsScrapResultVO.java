package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * @Description: 报废出库结果（XT-001）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
@Data
@Accessors(chain = true)
@Schema(description = "报废出库结果")
public class WmsScrapResultVO {

    @Schema(description = "报废出库单 id")
    private String outboundId;

    @Schema(description = "报废出库单号")
    private String outboundNo;

    @Schema(description = "出库类型（恒为「报废出库」）")
    private String outboundType;

    @Schema(description = "本次报废数量")
    private BigDecimal scrappedQty;

    @Schema(description = "报废后该库存行剩余数量")
    private BigDecimal remainingQty;

    @Schema(description = "金蝶其他出库单号；回写失败时为 null")
    private String erpBillNo;

    @Schema(description = "ERP 回写是否成功；失败不影响本地报废")
    private boolean syncSuccess;

    @Schema(description = "回写失败原因")
    private String syncError;
}
