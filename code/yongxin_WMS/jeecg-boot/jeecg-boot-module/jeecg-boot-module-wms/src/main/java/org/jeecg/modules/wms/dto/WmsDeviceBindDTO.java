package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * @Description: 设备绑定库区入参
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@Accessors(chain = true)
@Schema(description = "设备绑定库区入参")
public class WmsDeviceBindDTO {

    @Schema(description = "库区ID")
    private String warehouseId;

    @Schema(description = "设备ID列表")
    private List<String> deviceIds;
}