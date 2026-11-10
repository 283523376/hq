package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @Description: 设备状态上报入参（供 WCS/RMS 回传）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备状态上报入参")
public class WmsDeviceStatusDTO {

    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "状态：在线/离线/故障")
    private String status;
}