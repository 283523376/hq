package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.entity.WmsStocktakeDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 差异汇总与复核阈值边界（spec §5.3；含账面 0、阈值 0、合计口径）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeDiffCalculatorTest {

    private final WmsStocktakeDiffCalculator calculator = new WmsStocktakeDiffCalculator();

    @Test
    void summarizeShouldSplitGainAndLossAsAbsoluteTotals() {
        BigDecimal[] s = calculator.summarize(List.of(
                detail("10", "12"),   // 盈 2
                detail("10", "7")));  // 亏 3

        assertArrayEquals(new BigDecimal[]{
                new BigDecimal("20"), new BigDecimal("2"), new BigDecimal("3")}, s);
    }

    @Test
    void summarizeShouldIgnoreUnrecordedDetails() {
        BigDecimal[] s = calculator.summarize(List.of(detail("10", null), detail("5", "5")));

        assertArrayEquals(new BigDecimal[]{
                new BigDecimal("15"), BigDecimal.ZERO, BigDecimal.ZERO}, s);
    }

    @Test
    void summarizeEmptyShouldReturnZeros() {
        BigDecimal[] s = calculator.summarize(List.of());

        assertArrayEquals(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, s);
    }

    @Test
    void shouldReviewWhenRatioReachesThreshold() {
        // 账面 100，盈 2 亏 3 → 5% >= 5%
        assertTrue(calculator.needsReview(new BigDecimal("100"), new BigDecimal("2"),
                new BigDecimal("3"), new BigDecimal("5")));
    }

    @Test
    void shouldNotReviewWhenRatioBelowThreshold() {
        assertFalse(calculator.needsReview(new BigDecimal("100"), new BigDecimal("1"),
                BigDecimal.ZERO, new BigDecimal("5")));
    }

    @Test
    void shouldReviewWhenBookTotalIsZero() {
        assertTrue(calculator.needsReview(BigDecimal.ZERO, new BigDecimal("5"),
                BigDecimal.ZERO, new BigDecimal("5")));
    }

    @Test
    void shouldReviewWhenThresholdIsZeroOrMissing() {
        assertTrue(calculator.needsReview(new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO));
        assertTrue(calculator.needsReview(new BigDecimal("100"), BigDecimal.ONE,
                BigDecimal.ZERO, null));
    }

    @Test
    void shouldNotReviewWhenNoDifference() {
        assertFalse(calculator.needsReview(new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static WmsStocktakeDetail detail(String book, String actual) {
        WmsStocktakeDetail detail = new WmsStocktakeDetail();
        detail.setBookQty(new BigDecimal(book));
        detail.setActualQty(actual == null ? null : new BigDecimal(actual));
        return detail;
    }
}
