package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 出库单反向生成结果（销售出库待办等通用）。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
@Data
@Schema(description = "出库待办自动生成结果")
public class WmsDemandResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫描的 ERP 单据数")
    private int scanned;

    @Schema(description = "生成的出库单数")
    private int created;

    @Schema(description = "跳过数（单据/行级）")
    private int skipped;

    @Schema(description = "生成的出库单号")
    private List<String> createdOrders = new ArrayList<>();

    @Schema(description = "跳过原因摘要（最多 20 条）")
    private List<String> skippedReasons = new ArrayList<>();

    @Schema(description = "结果说明")
    private String message;
}
