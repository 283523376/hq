package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 出库关单入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "出库关单入参")
public class WmsOutboundCloseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "关单原因")
    private String reason;
}
