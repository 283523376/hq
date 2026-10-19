package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单据作废入参。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "单据作废入参")
public class WmsVoidDTO {

    /** 作废原因（必填） */
    @Schema(description = "作废原因（必填）")
    private String reason;
}
