package org.jeecg.modules.wms.sync;

import lombok.Getter;

/**
 * @Description: RMS 一次 HTTP 调用的结果（已解析 code/msg，保留 HTTP 状态与原始报文）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
@Getter
public class WmsRmsResponse {

    /** RMS 业务码；-1 表示未拿到有效响应。 */
    private final int code;

    /** RMS 返回的 msg 或本地错误描述。 */
    private final String message;

    /** HTTP 状态码；0 表示未发生 HTTP 调用（未配置/参数错误）。 */
    private final int httpStatus;

    /** 是否为传输层异常（超时、连接失败等）。 */
    private final boolean transportError;

    /** RMS 地址是否已配置。 */
    private final boolean configured;

    /** 原始响应体（排障用，可能为 null）。 */
    private final String rawBody;

    private WmsRmsResponse(int code, String message, int httpStatus,
                           boolean transportError, boolean configured, String rawBody) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
        this.transportError = transportError;
        this.configured = configured;
        this.rawBody = rawBody;
    }

    public static WmsRmsResponse of(int code, String message) {
        return of(code, message, 200, null);
    }

    public static WmsRmsResponse of(int code, String message, int httpStatus, String rawBody) {
        return new WmsRmsResponse(code, message, httpStatus, false, true, rawBody);
    }

    public static WmsRmsResponse notConfigured() {
        return new WmsRmsResponse(-1, "未配置 RMS 地址（wms.sync.rms.url）", 0, false, false, null);
    }

    public static WmsRmsResponse transportError(String message) {
        return new WmsRmsResponse(-1, message, 0, true, true, null);
    }

    public static WmsRmsResponse paramError(String message) {
        return new WmsRmsResponse(WmsRmsErrorCode.PARAM_ERROR, message, 400, false, true, null);
    }

    public boolean isSuccess() {
        return configured && !transportError && httpStatus >= 200 && httpStatus < 300
                && code == WmsRmsErrorCode.SUCCESS;
    }
}
