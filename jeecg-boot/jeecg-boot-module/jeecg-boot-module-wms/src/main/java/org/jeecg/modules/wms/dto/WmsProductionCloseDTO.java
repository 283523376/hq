package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 生产入库关单入参。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "生产入库关单入参")
public class WmsProductionCloseDTO {

    @Schema(description = "关单原因")
    private String reason;
}
