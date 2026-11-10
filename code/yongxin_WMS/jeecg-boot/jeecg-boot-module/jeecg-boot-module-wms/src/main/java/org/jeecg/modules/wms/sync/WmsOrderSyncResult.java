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

    /** 是否为模拟成功（未配置真实 ERP 地址时）；调用方可据此区分「真同步」与「演示态」 */
    @Schema(description = "是否为模拟成功")
    private boolean mocked;

    public static WmsOrderSyncResult ok() {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(true);
        return result;
    }

    /** 模拟成功：未接入真实 ERP 时用于闭环演示，并把「这是模拟」显式带出。 */
    public static WmsOrderSyncResult okMock() {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(true);
        result.setMocked(true);
        return result;
    }

    public static WmsOrderSyncResult fail(String errorMessage) {
        WmsOrderSyncResult result = new WmsOrderSyncResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
