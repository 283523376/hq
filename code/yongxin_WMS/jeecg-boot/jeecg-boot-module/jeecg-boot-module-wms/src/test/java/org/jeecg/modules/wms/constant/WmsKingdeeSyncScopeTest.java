package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 上位系统同步范围登记表单测——它是「同步范围」配置项的候选值来源，
 *               改了它前端下拉就变，所以口径必须锁住。
 * @Author: 永鑫WMS
 * @Date: 2026-10-27
 */
class WmsKingdeeSyncScopeTest {

    /** 候选值不能重复，否则 MULTI 里的勾选会出现两个同文案不同下标。 */
    @Test
    void labelsShouldBeUnique() {
        List<String> labels = WmsKingdeeSyncScope.labels();
        Set<String> distinct = new HashSet<>(labels);
        assertEquals(labels.size(), distinct.size(), "同步范围文案重复：" + labels);
        assertTrue(labels.stream().noneMatch(String::isBlank), "不允许空白文案");
    }

    /** 默认值只能包含「已启用」的范围：待确认/暂不启用/未实现的不能默认勾上。 */
    @Test
    void defaultValueShouldOnlyContainEnabledScopes() {
        Set<String> enabled = new HashSet<>();
        Set<String> notEnabled = new HashSet<>();
        for (WmsKingdeeSyncScope scope : WmsKingdeeSyncScope.values()) {
            (scope.getStatus() == WmsKingdeeSyncScope.Status.ENABLED ? enabled : notEnabled)
                    .add(scope.getOptionLabel());
        }
        for (String item : WmsKingdeeSyncScope.defaultValue().split(",")) {
            assertTrue(enabled.contains(item), "默认勾选了非「已启用」的范围：" + item);
            assertTrue(!notEnabled.contains(item));
        }
    }

    /** 每条范围都要绑定金蝶单据，前端显示的就是「同步哪张单据」，不允许空着。 */
    @Test
    void everyScopeShouldDeclareKingdeeForm() {
        for (WmsKingdeeSyncScope scope : WmsKingdeeSyncScope.values()) {
            assertNotNull(scope.getKingdeeForm(), scope.name() + " 未声明金蝶单据");
            assertTrue(!scope.getKingdeeForm().isBlank(), scope.name() + " 未声明金蝶单据");
            assertNotNull(scope.getDirection());
        }
    }

    /** code 是将来「按配置拦截」的取值口径，必须能反查；未知 code 返回 null 而不是抛异常。 */
    @Test
    void ofCodeShouldResolveAndReturnNullForUnknown() {
        assertEquals(WmsKingdeeSyncScope.PRODUCTION_INBOUND,
                WmsKingdeeSyncScope.ofCode("production-inbound"));
        assertNull(WmsKingdeeSyncScope.ofCode("no-such-scope"));
    }
}
