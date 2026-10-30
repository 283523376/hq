package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 工序汇报草稿推进入库入参（方案 B）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-30
 */
@Data
@Schema(description = "工序汇报草稿推进")
public class WmsProductionPromoteDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "载具码（必填，≤64）")
    private String carrierCode;
}
