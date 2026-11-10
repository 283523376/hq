package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 领料待办自动生成结果。
 *
 * @author 永鑫WMS
 * @since 2026-10-28
 */
@Data
@Schema(description = "领料待办自动生成结果")
public class WmsPickDemandResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫描的工单数")
    private int scanned;

    @Schema(description = "生成的领料单数")
    private int created;

    @Schema(description = "跳过的工单数（无缺口/工单状态不符/用料清单读取失败）")
    private int skipped;

    @Schema(description = "生成的领料单号")
    private List<String> createdOrders = new ArrayList<>();

    @Schema(description = "跳过原因摘要（逐单，最多 20 条）")
    private List<String> skippedReasons = new ArrayList<>();

    @Schema(description = "结果说明")
    private String message;
}
