package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: 设备绑定结果
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "设备绑定结果")
public class WmsDeviceBindResultVO {

    /** 成功绑定的设备数。**绑定成功与告警是两件事**：离线只产生告警，不影响本字段。 */
    @Schema(description = "成功绑定的设备数")
    private int bound;

    /** 本次从其它库区搬迁过来的设备编码（显式报出，不静默）。 */
    @Schema(description = "本次搬迁的设备编码")
    private List<String> movedDevices;

    /** 本次绑定涉及设备中当前离线的编码。 */
    @Schema(description = "当前离线的设备编码")
    private List<String> offlineDevices;

    /** 前端提示语；无离线设备时为 null。 */
    @Schema(description = "前端提示语")
    private String warning;
}