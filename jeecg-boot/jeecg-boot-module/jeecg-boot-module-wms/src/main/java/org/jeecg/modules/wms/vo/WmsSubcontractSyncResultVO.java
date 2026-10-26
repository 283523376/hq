package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 委外订单同步结果（外协出库骨架）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Data
@Schema(description = "委外订单同步结果")
public class WmsSubcontractSyncResultVO {

    @Schema(description = "金蝶返回的订单行数")
    private int queried;

    @Schema(description = "新建草稿数")
    private int created;

    @Schema(description = "更新草稿数")
    private int updated;

    @Schema(description = "按领料状态作废的草稿数")
    private int voided;

    @Schema(description = "逐单说明/跳过原因")
    private List<String> messages = new ArrayList<>();
}
