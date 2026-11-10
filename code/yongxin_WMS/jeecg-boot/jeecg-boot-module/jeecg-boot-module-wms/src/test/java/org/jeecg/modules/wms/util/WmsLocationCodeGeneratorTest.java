package org.jeecg.modules.wms.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @Description: 库位编码生成规则单测（验收标准①的核心）
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsLocationCodeGeneratorTest {

    // ------------------------------------------------ 已确认的样例必须逐字符一致

    /** 半成品立库：需求方确认过的样例，不得漂移。 */
    @Test
    void shouldMatchApprovedStereoSample() {
        assertEquals("LB-R01-C06-L24", WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24));
    }

    @Test
    void shouldMatchApprovedAgileSample() {
        assertEquals("MJ-R01-C60422-L10", WmsLocationCodeGenerator.locationCode("MJ", 1, 60422, 10));
    }

    @Test
    void shouldMatchLowLayerSample() {
        assertEquals("LB-R01-C06-L01", WmsLocationCodeGenerator.locationCode("LB", 1, 6, 1));
    }

    // ------------------------------------------------ 补零：只补不截

    @Test
    void shouldPadSingleDigitToTwo() {
        assertEquals("01", WmsLocationCodeGenerator.padSegment(1));
        assertEquals("06", WmsLocationCodeGenerator.padSegment(6));
        assertEquals("09", WmsLocationCodeGenerator.padSegment(9));
    }

    @Test
    void shouldNotPadTwoDigits() {
        assertEquals("10", WmsLocationCodeGenerator.padSegment(10));
        assertEquals("24", WmsLocationCodeGenerator.padSegment(24));
        assertEquals("99", WmsLocationCodeGenerator.padSegment(99));
    }

    /** 关键：超过 2 位**不得截断**，否则 100 层与 10 层会撞成同一个编码。 */
    @Test
    void shouldNeverTruncateBeyondTwoDigits() {
        assertEquals("100", WmsLocationCodeGenerator.padSegment(100));
        assertEquals("106", WmsLocationCodeGenerator.padSegment(106));
        assertEquals("60422", WmsLocationCodeGenerator.padSegment(60422));
    }

    // ------------------------------------------------ 单射性 → 唯一性不依赖外部配置

    /**
     * 编码是坐标的单射：不同坐标必得不同编码。
     *
     * <p>这一条替代了「靠库区配置保证唯一」的旧思路 —— 唯一性现在是编码规则自身的性质，
     * 与库区怎么配无关。若哪天有人把 {@code padSegment} 改成会截断的实现，本用例立刻红。
     */
    @Test
    void shouldBeInjectiveOnCoordinates() {
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 6),
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 606));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 10),
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 100));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24),
                WmsLocationCodeGenerator.locationCode("LB", 1, 60, 24));
        assertNotEquals(
                WmsLocationCodeGenerator.locationCode("LB", 1, 6, 24),
                WmsLocationCodeGenerator.locationCode("MJ", 1, 6, 24));
    }

    // ------------------------------------------------ 幂等：同输入必同输出

    @Test
    void shouldBeIdempotentForSameCoordinates() {
        assertEquals(
                WmsLocationCodeGenerator.locationCode("LB", 3, 7, 15),
                WmsLocationCodeGenerator.locationCode("LB", 3, 7, 15));
    }

    // ------------------------------------------------ 非法输入

    @Test
    void shouldRejectNullSegments() {
        assertThrows(IllegalArgumentException.class, () -> WmsLocationCodeGenerator.padSegment(null));
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode("LB", null, 6, 24));
    }

    @Test
    void shouldRejectBlankWarehouseCode() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode("  ", 1, 6, 24));
        assertThrows(IllegalArgumentException.class,
                () -> WmsLocationCodeGenerator.locationCode(null, 1, 6, 24));
    }
}