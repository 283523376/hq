package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Description: 预警处理入参。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
@Data
@Schema(description = "预警处理入参")
public class WmsWarningHandleDTO {

    @Schema(description = "预警ID")
    private String id;

    @Schema(description = "处理方式：REPLENISH 补料 / CLEAR 清理 / OTHER 其他")
    private String handleType;

    @Schema(description = "处理说明（必填，留痕审计）")
    private String handleRemark;
}
