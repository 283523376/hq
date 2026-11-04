package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 配置项元数据单测——穷举「分组-键」映射与默认值，防止改枚举时悄悄改口径
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
class WmsConfigKeyTest {

    /** 六组必须各就各位：库存 3 项、同步 6 项、不良品 1 项、设备 2 项、审计 1 项、金蝶单据 5 项，共 18 项。
     *  （不良品组原有「是否入线边柜」，随线边柜方案移出范围已于 2026-10-27 删除；
     *   审计组随 XT-002 于 2026-11-02 新增，保留期必须前端可调；
     *   同步组随 SB-001 于 2026-11-03 新增 4 项：自动对账范围 + 三个保留期；
     *   金蝶单据组随 V2.4 于 2026-11-04 新增 5 个 formId，默认空串 = 未配置） */
    @Test
    void shouldExposeNineKeysInFiveGroups() {
        assertEquals(18, WmsConfigKey.ordered().length);
        assertEquals(3, WmsConfigKey.ofGroup(WmsConfigGroup.INVENTORY).size());
        assertEquals(6, WmsConfigKey.ofGroup(WmsConfigGroup.SYNC).size());
        assertEquals(1, WmsConfigKey.ofGroup(WmsConfigGroup.DEFECT).size());
        assertEquals(2, WmsConfigKey.ofGroup(WmsConfigGroup.DEVICE).size());
        assertEquals(1, WmsConfigKey.ofGroup(WmsConfigGroup.AUDIT).size());
        assertEquals(5, WmsConfigKey.ofGroup(WmsConfigGroup.KINGDEE).size());
    }

    /** 键必须唯一——重复键会让「按 key 取值」出现两个答案。 */
    @Test
    void keysShouldBeUnique() {
        long distinct = Arrays.stream(WmsConfigKey.ordered()).map(WmsConfigKey::getKey).distinct().count();
        assertEquals(18, distinct);
    }

    /** 设备两项的默认值必须与既有常量一致，否则「DB 无配置」时行为会静默改变。 */
    @Test
    void deviceDefaultsShouldMatchConstants() {
        assertEquals(String.valueOf(WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS),
                WmsConfigKey.DEVICE_OFFLINE_THRESHOLD_SECONDS.getDefaultValue());
        assertEquals(String.valueOf(WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES),
                WmsConfigKey.DEVICE_ALERT_THROTTLE_MINUTES.getDefaultValue());
    }

    /** 多选项必须声明候选值，否则前端无法渲染下拉；候选值只来自同步范围登记表。 */
    @Test
    void multiTypeShouldDeclareOptions() {
        WmsConfigKey scope = WmsConfigKey.SYNC_SCOPE;
        assertEquals(WmsConfigType.MULTI, scope.getType());
        assertEquals(WmsKingdeeSyncScope.labels(), scope.getOptions());
        for (String defaultItem : scope.getDefaultValue().split(",")) {
            assertTrue(scope.getOptions().contains(defaultItem), "默认值必须是候选值之一：" + defaultItem);
        }
    }

    /** 未知键必须在元数据层就拒绝，而不是等到校验器里 NPE。 */
    @Test
    void ofShouldRejectUnknownKey() {
        assertThrows(IllegalArgumentException.class, () -> WmsConfigKey.of("no.such.key"));
        assertThrows(IllegalArgumentException.class, () -> WmsConfigGroup.of("no-such-group"));
    }

    /** 分组顺序固定：前端左侧菜单按此顺序渲染，不依赖枚举声明顺序的偶然性。 */
    @Test
    void groupsShouldBeOrdered() {
        List<WmsConfigGroup> groups = Arrays.asList(WmsConfigGroup.ordered());
        assertEquals(WmsConfigGroup.INVENTORY, groups.get(0));
        assertEquals(WmsConfigGroup.DEVICE, groups.get(3));
        assertEquals(WmsConfigGroup.AUDIT, groups.get(4));
        assertEquals(WmsConfigGroup.KINGDEE, groups.get(5));
        assertEquals(6, groups.size());
    }
}
