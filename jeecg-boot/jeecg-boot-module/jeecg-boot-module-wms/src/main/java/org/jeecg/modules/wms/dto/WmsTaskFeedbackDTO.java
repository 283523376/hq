package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * @Description: 设备任务反馈入参（RMS V1.6 / WCS 占位契约）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "设备任务反馈")
public class WmsTaskFeedbackDTO {

    @Schema(description = "任务明细")
    private List<Detail> details;

    @Data
    @Schema(description = "任务反馈明细")
    public static class Detail {

        @Schema(description = "任务号")
        private String taskNo;

        @Schema(description = "容器编码（RMS）")
        private String containerCode;

        @Schema(description = "载具码（WCS）")
        private String carrierCode;

        @Schema(description = "任务状态：RUNNING/OUTSTATION/FINISHED/FAILED")
        private String taskStatus;

        @Schema(description = "工作站编码")
        private String stationCode;

        @Schema(description = "库位编码（RMS）")
        private String stackCode;

        @Schema(description = "库位编码（WCS）")
        private String locationCode;

        @Schema(description = "失败原因")
        private String message;

        @Schema(description = "单重")
        private BigDecimal weight;

        @Schema(description = "层高")
        private Integer layer;
    }
}
