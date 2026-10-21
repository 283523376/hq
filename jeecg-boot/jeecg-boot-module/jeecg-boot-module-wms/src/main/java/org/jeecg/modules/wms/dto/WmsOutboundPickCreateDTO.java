package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * @Description: CK-001 生产领料单创建入参
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
@Data
@Schema(description = "生产领料单创建入参")
public class WmsOutboundPickCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "生产工单号")
    private String workOrderNo;

    @Schema(description = "需求来源：金蝶用料清单/手工")
    private String requirementSource;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "领料明细")
    private List<WmsOutboundDetailDTO> details;
}
