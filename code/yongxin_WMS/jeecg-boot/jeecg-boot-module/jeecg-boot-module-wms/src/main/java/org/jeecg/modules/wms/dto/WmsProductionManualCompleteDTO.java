package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 转人工完成入参。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "转人工完成入参")
public class WmsProductionManualCompleteDTO {

    @Schema(description = "新的载具码（为空则沿用提交时的值）")
    private String carrierCode;

    @Schema(description = "人工确认说明")
    private String remark;
}
