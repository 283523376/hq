package org.jeecg.modules.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 订单审核入参
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Schema(description = "订单审核入参")
public class WmsOrderAuditDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单ID */
    @Schema(description = "订单ID")
    private String id;

    /** 是否通过：true 审核通过 false 驳回 */
    @Schema(description = "是否通过：true 审核通过 false 驳回")
    private Boolean pass;

    /** 审核意见（驳回时必填） */
    @Schema(description = "审核意见（驳回时必填）")
    private String auditRemark;
}
