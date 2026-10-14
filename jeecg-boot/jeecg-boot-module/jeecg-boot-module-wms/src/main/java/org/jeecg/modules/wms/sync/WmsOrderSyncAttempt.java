package org.jeecg.modules.wms.sync;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;

/**
 * @Description: 一次同步流程的结果（含实际重试次数）。
 *
 * <p>刻意放在 sync 包下作为顶层类，而不是实现类的嵌套类——否则调用方（订单服务、定时任务）
 * 必须依赖具体实现类才能引用本类型，破坏「依赖接口」的边界。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-14
 */
@Data
@AllArgsConstructor
public class WmsOrderSyncAttempt implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否最终成功 */
    private final boolean success;

    /** 失败原因；成功时为 null */
    private final String errorMessage;

    /** 本次流程实际重试次数（0 表示首次即成功） */
    private final int retryCount;
}
