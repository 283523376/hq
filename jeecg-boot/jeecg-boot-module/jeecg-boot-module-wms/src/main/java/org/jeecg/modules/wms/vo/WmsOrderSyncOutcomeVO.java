package org.jeecg.modules.wms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * @Description: 订单同步结果（下达 / 重新同步的响应体）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单同步结果")
public class WmsOrderSyncOutcomeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否同步成功 */
    @Schema(description = "是否同步成功")
    private Boolean synced;

    /** 同步失败原因；成功时为空 */
    @Schema(description = "同步失败原因；成功时为空")
    private String syncError;

    /** 本次同步实际重试次数 */
    @Schema(description = "本次同步实际重试次数")
    private Integer retryCount;
}
