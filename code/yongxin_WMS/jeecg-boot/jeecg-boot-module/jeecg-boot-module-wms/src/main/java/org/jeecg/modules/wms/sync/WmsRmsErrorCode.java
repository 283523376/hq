package org.jeecg.modules.wms.sync;

import java.util.Map;

/**
 * @Description: 米塔 RMS 任务接口 V1.6 错误码与分类。
 *
 * <p>分类口径：
 * <ul>
 *   <li>入库任务 0 / 300001 视为幂等成功（任务号已存在说明上一次已受理）；</li>
 *   <li>强制入库 0 / 300012 视为幂等成功（任务已呼叫机器人对接）；</li>
 *   <li>只有 300000 可重试；其余业务错误码原样透出，转人工处理。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
public final class WmsRmsErrorCode {

    public static final int SUCCESS = 0;
    public static final int PARAM_ERROR = 100001;
    public static final int RETRYABLE = 300000;
    public static final int TASK_EXISTS = 300001;
    public static final int TASK_NOT_EXISTS = 300002;
    public static final int STATION_NOT_EXISTS = 300003;
    public static final int STATION_MODE_MISMATCH = 300004;
    public static final int CONTAINER_EXISTS = 300006;
    public static final int CONTAINER_NOT_EXISTS = 300007;
    public static final int CONTAINER_LOCKED = 300008;
    public static final int TASK_ALREADY_CALLED = 300012;
    public static final int ZONE_NOT_EXISTS = 300013;
    public static final int STACK_NOT_EXISTS = 300017;

    private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(SUCCESS, "成功"),
            Map.entry(PARAM_ERROR, "参数错误"),
            Map.entry(RETRYABLE, "接口调用失败，可重试"),
            Map.entry(TASK_EXISTS, "任务号已存在"),
            Map.entry(TASK_NOT_EXISTS, "任务号不存在"),
            Map.entry(STATION_NOT_EXISTS, "工作站编码不存在"),
            Map.entry(STATION_MODE_MISMATCH, "工作站作业模式不匹配"),
            Map.entry(CONTAINER_EXISTS, "容器编码已存在"),
            Map.entry(CONTAINER_NOT_EXISTS, "容器编码不存在"),
            Map.entry(CONTAINER_LOCKED, "容器已被其他出库任务锁定"),
            Map.entry(TASK_ALREADY_CALLED, "任务已呼叫机器人对接"),
            Map.entry(ZONE_NOT_EXISTS, "区域编码不存在"),
            Map.entry(STACK_NOT_EXISTS, "库位编码不存在")
    );

    private WmsRmsErrorCode() {
    }

    public static String describe(int code) {
        return DESCRIPTIONS.getOrDefault(code, "未知错误码");
    }

    public static boolean isRetryable(int code) {
        return code == RETRYABLE;
    }

    /** 入库任务：0 或 300001 都表示这条任务已经在 RMS 侧被受理。 */
    public static boolean isInboundIdempotentSuccess(int code) {
        return code == SUCCESS || code == TASK_EXISTS;
    }

    /** 强制入库：0 或 300012（已在呼叫机器人）都表示无需再次下发。 */
    public static boolean isForceIdempotentSuccess(int code) {
        return code == SUCCESS || code == TASK_ALREADY_CALLED;
    }

    /** 出库任务：0 或 300001 都表示这条任务已经在 RMS 侧被受理。 */
    public static boolean isOutboundIdempotentSuccess(int code) {
        return code == SUCCESS || code == TASK_EXISTS;
    }

    /** 搬运（移库）任务：0 或 300001（任务号已存在）都表示已受理。 */
    public static boolean isTransferIdempotentSuccess(int code) {
        return code == SUCCESS || code == TASK_EXISTS;
    }
}
