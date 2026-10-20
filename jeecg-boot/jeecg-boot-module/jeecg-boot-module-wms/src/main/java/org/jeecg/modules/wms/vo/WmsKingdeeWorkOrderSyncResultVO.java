package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 金蝶工单同步结果。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Data
@Schema(description = "金蝶工单同步结果")
public class WmsKingdeeWorkOrderSyncResultVO {

    @Schema(description = "查询到的工单行数")
    private int queried;

    @Schema(description = "新增工单数")
    private int imported;

    @Schema(description = "更新工单数")
    private int updated;

    @Schema(description = "跳过数")
    private int skipped;

    @Schema(description = "跳过/失败原因")
    private List<String> messages = new ArrayList<>();
}
