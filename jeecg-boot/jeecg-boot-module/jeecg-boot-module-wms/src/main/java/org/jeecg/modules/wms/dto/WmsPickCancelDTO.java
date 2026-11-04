package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: CK-001 领料取消入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
@Data
@Schema(description = "领料取消入参")
public class WmsPickCancelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "取消原因（领料物料不符/库存不足等）")
    private String reason;
}
