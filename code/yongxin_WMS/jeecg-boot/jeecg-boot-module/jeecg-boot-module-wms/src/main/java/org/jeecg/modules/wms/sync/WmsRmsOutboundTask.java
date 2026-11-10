package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @Description: RMS 出库任务明细（V1.6 §3.1）
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "RMS 出库任务明细")
public class WmsRmsOutboundTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务号（必填）")
    private String taskNo;

    @Schema(description = "容器编码（必填）")
    private String containerCode;

    @Schema(description = "优先级 1~5")
    private Integer priority;

    @Schema(description = "工作站编码列表（可选，最多 10）")
    private List<String> stationCodes;
}
