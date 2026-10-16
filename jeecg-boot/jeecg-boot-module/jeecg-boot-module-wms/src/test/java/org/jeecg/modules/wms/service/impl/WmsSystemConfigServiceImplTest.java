package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.entity.WmsSystemConfig;
import org.jeecg.modules.wms.vo.WmsConfigGroupVO;
import org.jeecg.modules.wms.vo.WmsConfigItemVO;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * @Description: 系统配置读路径单测（不启 Spring、不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
class WmsSystemConfigServiceImplTest {

    private static WmsSystemConfig row(String key, String value) {
        WmsSystemConfig c = new WmsSystemConfig().setConfigKey(key).setConfigValue(value);
        c.setId("id-" + key);
        return c;
    }

    /** 库里有值时用库里的值，且把版本号与「是否可回滚」一起带回。 */
    @Test
    void definitionsShouldReturnStoredValue() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.singletonList(row("inv.stock.min", "42"))).when(service).list(any(Wrapper.class));
        // 兜底桩必须在前：其余 8 个键也得有返回值，否则会走真实实现去查未注入的 mapper
        doReturn(0).when(service).currentVersionNo(any());
        doReturn(1).when(service).currentVersionNo("inv.stock.min");

        List<WmsConfigGroupVO> groups = service.definitions();

        assertEquals(4, groups.size());
        WmsConfigItemVO min = groups.get(0).getItems().stream()
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();
        assertEquals("42", min.getValue());
        assertEquals("最低库存", min.getLabel());
        assertTrue(!min.isCanRollback(), "只有一个版本时不可回滚");
    }

    /** 库里没有该键时必须回落元数据里的默认值——否则新环境一打开页面全是空。 */
    @Test
    void definitionsShouldFallBackToDefault() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        WmsConfigItemVO auto = service.definitions().stream()
                .flatMap(g -> g.getItems().stream())
                .filter(i -> "sync.auto".equals(i.getKey())).findFirst().orElseThrow();

        assertEquals("true", auto.getValue(), "无配置应回落元数据默认值");
    }

    /** 多选项要把候选值带给前端，否则下拉没得选。 */
    @Test
    void definitionsShouldCarryOptionsForMultiType() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        List<WmsConfigItemVO> all = service.definitions().stream()
                .flatMap(g -> g.getItems().stream()).toList();
        WmsConfigItemVO scope = all.stream()
                .filter(i -> "sync.scope".equals(i.getKey())).findFirst().orElseThrow();
        WmsConfigItemVO min = all.stream()
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();

        assertEquals(3, scope.getOptions().size());
        assertNull(min.getOptions());
    }

    /** listByGroup 只返回该组的项，且顺序与元数据一致。 */
    @Test
    void listByGroupShouldReturnOnlyThatGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());

        List<WmsConfigItemVO> items = service.listByGroup("device");

        assertEquals(2, items.size());
        assertEquals("device.offline.threshold.seconds", items.get(0).getKey());
        assertEquals("600", items.get(0).getValue(), "默认值须与既有常量同值");
    }

    /** 未知分组必须被拒。 */
    @Test
    void listByGroupShouldRejectUnknownGroup() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());

        assertThrows(IllegalArgumentException.class, () -> service.listByGroup("nope"));
    }

    /** 版本号 ≥2 时标记为可回滚。 */
    @Test
    void definitionsShouldMarkRollbackableWhenVersionAboveOne() {
        WmsSystemConfigServiceImpl service = spy(new WmsSystemConfigServiceImpl());
        doReturn(Collections.emptyList()).when(service).list(any(Wrapper.class));
        doReturn(0).when(service).currentVersionNo(any());
        doReturn(2).when(service).currentVersionNo("inv.stock.min");

        WmsConfigItemVO min = service.definitions().stream().flatMap(g -> g.getItems().stream())
                .filter(i -> "inv.stock.min".equals(i.getKey())).findFirst().orElseThrow();

        assertTrue(min.isCanRollback());
    }
}
