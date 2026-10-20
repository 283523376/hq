package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: RMS V1.6 错误码分类断言。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsRmsErrorCodeTest {

    @Test
    void inboundIdempotentSuccess_onlyForSuccessAndTaskExists() {
        assertTrue(WmsRmsErrorCode.isInboundIdempotentSuccess(0));
        assertTrue(WmsRmsErrorCode.isInboundIdempotentSuccess(300001));
        assertFalse(WmsRmsErrorCode.isInboundIdempotentSuccess(300006));
        assertFalse(WmsRmsErrorCode.isInboundIdempotentSuccess(300000));
    }

    @Test
    void forceIdempotentSuccess_includesAlreadyCalled() {
        assertTrue(WmsRmsErrorCode.isForceIdempotentSuccess(0));
        assertTrue(WmsRmsErrorCode.isForceIdempotentSuccess(300012));
        assertFalse(WmsRmsErrorCode.isForceIdempotentSuccess(300001));
    }

    @Test
    void retryable_onlyFor300000() {
        assertTrue(WmsRmsErrorCode.isRetryable(300000));
        assertFalse(WmsRmsErrorCode.isRetryable(300001));
        assertFalse(WmsRmsErrorCode.isRetryable(300006));
    }

    @Test
    void describe_knownAndUnknownCodes() {
        assertEquals("任务号已存在", WmsRmsErrorCode.describe(300001));
        assertEquals("容器编码已存在", WmsRmsErrorCode.describe(300006));
        assertEquals("未知错误码", WmsRmsErrorCode.describe(999999));
    }
}
