package org.jeecg.modules.wms.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 设备任务下发结果（RMS/WCS 网关返回）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
@Data
@Schema(description = "设备任务下发结果")
public class WmsTaskDispatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "是否为模拟受理（未配置真实地址）")
    private boolean mocked;

    @Schema(description = "对方错误码")
    private Integer errorCode;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "是否可重试")
    private boolean retryable;

    public static WmsTaskDispatchResult ok() {
        WmsTaskDispatchResult result = new WmsTaskDispatchResult();
        result.setSuccess(true);
        return result;
    }

    public static WmsTaskDispatchResult okMock() {
        WmsTaskDispatchResult result = new WmsTaskDispatchResult();
        result.setSuccess(true);
        result.setMocked(true);
        return result;
    }

    public static WmsTaskDispatchResult fail(Integer errorCode, String errorMessage, boolean retryable) {
        WmsTaskDispatchResult result = new WmsTaskDispatchResult();
        result.setSuccess(false);
        result.setErrorCode(errorCode);
        result.setErrorMessage(errorMessage);
        result.setRetryable(retryable);
        return result;
    }
}
