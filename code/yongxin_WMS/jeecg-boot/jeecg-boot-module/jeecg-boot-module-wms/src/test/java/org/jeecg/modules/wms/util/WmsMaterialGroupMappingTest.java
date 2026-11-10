package org.jeecg.modules.wms.util;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 金蝶物料分组映射串解析单测：正常/格式错/类型非法/空。
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
class WmsMaterialGroupMappingTest {

    @Test
    void shouldParseNormalMapping() {
        Map<String, String> mapping = WmsMaterialGroupMapping.parse(
                "XA.00.01010101=原材料;A.C1.1.Y75=半成品;75=辅料;CP.01=成品");

        assertEquals(4, mapping.size());
        assertEquals(WmsConstants.CATEGORY_TYPE_RAW, mapping.get("XA.00.01010101"));
        assertEquals(WmsConstants.CATEGORY_TYPE_HALF_FINISHED, mapping.get("A.C1.1.Y75"));
        assertEquals(WmsConstants.CATEGORY_TYPE_AUXILIARY, mapping.get("75"));
        assertEquals(WmsConstants.CATEGORY_TYPE_PRODUCT, mapping.get("CP.01"));
    }

    /** 空白与空串都是合法初始态：全部不映射，不告警也不报错。 */
    @Test
    void shouldReturnEmptyForBlank() {
        assertTrue(WmsMaterialGroupMapping.parse(null).isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("   ").isEmpty());
    }

    @Test
    void shouldAcceptChineseSemicolonAndTrimWhitespace() {
        Map<String, String> mapping = WmsMaterialGroupMapping.parse(
                " XA.01 = 原材料 ； A.02=半成品 ");

        assertEquals(2, mapping.size());
        assertEquals(WmsConstants.CATEGORY_TYPE_RAW, mapping.get("XA.01"));
        assertEquals(WmsConstants.CATEGORY_TYPE_HALF_FINISHED, mapping.get("A.02"));
    }

    /** 任一条格式错 → 整串忽略：部分生效比不生效更难排查。 */
    @Test
    void shouldIgnoreWholeMappingWhenAnyEntryIsMalformed() {
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;缺等号").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;=半成品").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;XA.02=").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;;XA.02=半成品").isEmpty());
    }

    /** 类型非法同样整串忽略，绝不猜成「原材料」。 */
    @Test
    void shouldIgnoreWholeMappingWhenAnyTypeIsInvalid() {
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;XA.02=产成品").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;XA.02=RAW").isEmpty());
        assertTrue(WmsMaterialGroupMapping.parse("XA.01=原材料;XA.02=").isEmpty());
    }

    /** 重复键后值覆盖前值（管理员修正配置时常见），不视为格式错。 */
    @Test
    void shouldLetLaterDuplicateKeyOverrideEarlier() {
        Map<String, String> mapping = WmsMaterialGroupMapping.parse("XA.01=原材料;XA.01=半成品");

        assertEquals(1, mapping.size());
        assertEquals(WmsConstants.CATEGORY_TYPE_HALF_FINISHED, mapping.get("XA.01"));
    }
}
