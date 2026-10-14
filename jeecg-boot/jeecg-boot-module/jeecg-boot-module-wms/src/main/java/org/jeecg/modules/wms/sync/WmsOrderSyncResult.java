package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 一次同步尝试的结果（SPI 的返回类型）
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@Schema(description = "一次同步尝试的结果")
public class WmsOrderSyncResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    @Schema(description = "是否成功")
    private boolean success;

    /** 失败原因；成功时为 null */
    @Schema(description = "失败原因；成功时为 null")
    private String errorMessage;

    public static WmsOrderSyncResult ok() {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(true);
        return result;
    }

    public static WmsOrderSyncResult fail(String errorMessage) {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
