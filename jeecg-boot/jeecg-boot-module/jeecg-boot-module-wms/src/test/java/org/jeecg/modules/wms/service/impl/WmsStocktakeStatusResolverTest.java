package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 盘点状态机白名单（spec 2026-10-26 §5.3）：含复核回退的完整合法链
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeStatusResolverTest {

    private final WmsStocktakeStatusResolver resolver = new WmsStocktakeStatusResolver();

    @Test
    void shouldAllowAllLegalTransitions() {
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_DRAFT, WmsConstants.STOCKTAKE_STATUS_COUNTING));
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_COUNTING, WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED));
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_COUNTING, WmsConstants.STOCKTAKE_STATUS_REVIEWING));
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, WmsConstants.STOCKTAKE_STATUS_REVIEWING));
        // 复核通过后回退给调整环节
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_REVIEWING, WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED));
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED, WmsConstants.STOCKTAKE_STATUS_ADJUSTED));
        assertDoesNotThrow(() -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_ADJUSTED, WmsConstants.STOCKTAKE_STATUS_ARCHIVED));
    }

    @Test
    void shouldRejectIllegalTransitions() {
        assertThrows(RuntimeException.class, () -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_DRAFT, WmsConstants.STOCKTAKE_STATUS_ADJUSTED));
        assertThrows(RuntimeException.class, () -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_DRAFT, WmsConstants.STOCKTAKE_STATUS_ARCHIVED));
        assertThrows(RuntimeException.class, () -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_COUNTING, WmsConstants.STOCKTAKE_STATUS_ARCHIVED));
    }

    @Test
    void archivedIsTerminal() {
        assertTrue(resolver.isTerminal(WmsConstants.STOCKTAKE_STATUS_ARCHIVED));
        assertFalse(resolver.isTerminal(WmsConstants.STOCKTAKE_STATUS_COUNTING));
        assertThrows(RuntimeException.class, () -> resolver.assertTransition(
                WmsConstants.STOCKTAKE_STATUS_ARCHIVED, WmsConstants.STOCKTAKE_STATUS_COUNTING));
    }
}
