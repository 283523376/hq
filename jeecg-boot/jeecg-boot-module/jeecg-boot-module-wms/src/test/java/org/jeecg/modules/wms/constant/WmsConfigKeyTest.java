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

    /** 四组必须各就各位：库存 3 项、同步 2 项、不良品 1 项、设备 2 项，共 8 项。
     *  （不良品组原有「是否入线边柜」，随线边柜方案移出范围已于 2026-10-27 删除） */
    @Test
    void shouldExposeEightKeysInFourGroups() {
        assertEquals(8, WmsConfigKey.ordered().length);
        assertEquals(3, WmsConfigKey.ofGroup(WmsConfigGroup.INVENTORY).size());
        assertEquals(2, WmsConfigKey.ofGroup(WmsConfigGroup.SYNC).size());
        assertEquals(1, WmsConfigKey.ofGroup(WmsConfigGroup.DEFECT).size());
        assertEquals(2, WmsConfigKey.ofGroup(WmsConfigGroup.DEVICE).size());
    }

    /** 键必须唯一——重复键会让「按 key 取值」出现两个答案。 */
    @Test
    void keysShouldBeUnique() {
        long distinct = Arrays.stream(WmsConfigKey.ordered()).map(WmsConfigKey::getKey).distinct().count();
        assertEquals(8, distinct);
    }

    /** 设备两项的默认值必须与既有常量一致，否则「DB 无配置」时行为会静默改变。 */
    @Test
    void deviceDefaultsShouldMatchConstants() {
        assertEquals(String.valueOf(WmsConstants.DEVICE_OFFLINE_THRESHOLD_SECONDS),
                WmsConfigKey.DEVICE_OFFLINE_THRESHOLD_SECONDS.getDefaultValue());
        assertEquals(String.valueOf(WmsConstants.DEVICE_ALERT_THROTTLE_MINUTES),
                WmsConfigKey.DEVICE_ALERT_THROTTLE_MINUTES.getDefaultValue());
    }

    /** 多选项必须声明候选值，否则前端无法渲染下拉。 */
    @Test
    void multiTypeShouldDeclareOptions() {
        WmsConfigKey scope = WmsConfigKey.SYNC_SCOPE;
        assertEquals(WmsConfigType.MULTI, scope.getType());
        assertEquals(Arrays.asList("库存账", "出入库记录", "柜内库存"), scope.getOptions());
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
        assertTrue(groups.size() == 4);
    }
}
