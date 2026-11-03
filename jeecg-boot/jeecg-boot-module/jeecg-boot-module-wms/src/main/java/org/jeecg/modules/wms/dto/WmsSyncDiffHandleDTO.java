package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: SB-001 差异标记处理入参（spec 2026-11-03 §2.8）。
 *
 * <p>状态只能「待核对 → 已核对/已忽略」，备注必填。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Data
@Schema(description = "差异标记处理入参")
public class WmsSyncDiffHandleDTO {

    @Schema(description = "目标状态：已核对/已忽略")
    private String targetStatus;

    @Schema(description = "处理备注（必填）")
    private String remark;
}
