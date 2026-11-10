package org.jeecg.modules.wms.sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: master-data 配置的**真实 YAML 绑定形态**单测。
 *
 * <p>为什么需要它：{@code WmsKingdeePropertiesMasterDataTest} 只 new 对象后 set，
 * 从不经过 Binder，因此「文档写成标量」这种会让应用启动失败的写法测不出来。
 *
 * <p>两个容易被写错的点：
 * <ul>
 *   <li>{@code Map} 字段写成标量（{@code stock-code-map: ""}）无法转换 → 启动失败；</li>
 *   <li>金蝶仓库编码带点（02.001 / X.2.4），键必须用方括号包起来，否则会被当成嵌套层级。</li>
 * </ul>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsKingdeePropertiesBindingTest {

    private static final String PREFIX = "wms.sync.erp.kingdee.master-data";

    @Test
    void shouldBindBracketKeyedMapBecauseKingdeeCodesContainDots() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PREFIX + ".stock-code-map.[X.2.4]", "TC");
        env.setProperty(PREFIX + ".stock-code-map.[02.001]", "MJ");
        env.setProperty(PREFIX + ".org-id", "101");

        WmsKingdeeProperties.MasterData md = Binder.get(env)
                .bind(PREFIX, WmsKingdeeProperties.MasterData.class).get();

        assertEquals("TC", md.getStockCodeMap().get("X.2.4"));
        assertEquals("MJ", md.getStockCodeMap().get("02.001"));
        assertEquals("101", md.getOrgId());
    }

    @Test
    void scalarValueForMapFieldMustFailFast() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PREFIX + ".stock-code-map", "");

        // 空串同样无法转换成 Map —— 这正是文档里不能写 `stock-code-map: ""` 的原因
        BindException e = assertThrows(BindException.class,
                () -> Binder.get(env).bind(PREFIX, WmsKingdeeProperties.MasterData.class).get());
        assertTrue(String.valueOf(e.getMessage()).contains("stock-code-map"), e.getMessage());
    }

    @Test
    void omittingMapFieldLeavesItEmpty() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PREFIX + ".org-id", "101");

        WmsKingdeeProperties.MasterData md = Binder.get(env)
                .bind(PREFIX, WmsKingdeeProperties.MasterData.class).get();

        assertTrue(md.getStockCodeMap().isEmpty());
        assertTrue(md.getCategoryMap().isEmpty());
    }
}
