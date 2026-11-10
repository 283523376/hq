package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: RMS 搬运（移库）任务明细（RMS任务接口文档 V1.6 §4.3）。
 *
 * <p>米塔只能指定目标<b>区域</b>（{@code zoneCode}），不能指定具体库位；跨仓段不经过米塔。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
@Data
@Schema(description = "RMS 搬运任务明细")
public class WmsRmsTransferTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务号（必填）")
    private String taskNo;

    @Schema(description = "容器编码（必填）")
    private String containerCode;

    @Schema(description = "明细级区域编码（可选，缺省用顶层）")
    private String zoneCode;

    @Schema(description = "优先级 1~5")
    private Integer priority;
}
