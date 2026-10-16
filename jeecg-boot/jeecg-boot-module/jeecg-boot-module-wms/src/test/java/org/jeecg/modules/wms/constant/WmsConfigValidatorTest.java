package org.jeecg.modules.wms.constant;

import org.jeecg.modules.wms.vo.WmsConfigConflict;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 配置校验器穷举测试（纯函数，不连库）
 * @Author: 永鑫WMS
 * @Date: 2026-10-16
 */
class WmsConfigValidatorTest {

    private static Map<String, String> inventory(String min, String safe, String max) {
        Map<String, String> m = new HashMap<>();
        m.put("inv.stock.min", min);
        m.put("inv.stock.safe", safe);
        m.put("inv.stock.max", max);
        return m;
    }

    // ------------------------------------------------------------ 库存三项的自洽

    /** 正常区间必须通过：0 ≤ 10 ≤ 50 ≤ 200。 */
    @Test
    void shouldAcceptValidInventoryRange() {
        assertTrue(WmsConfigValidator.validate("inventory", inventory("10", "50", "200")).isEmpty());
    }

    /** 最低 > 安全 必须判为冲突（蓝图「阈值非法→提示」）。 */
    @Test
    void shouldRejectWhenMinGreaterThanSafe() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", inventory("100", "50", "200"));
        assertEquals(1, conflicts.size());
        assertEquals("inv.stock.min", conflicts.get(0).getKey());
        assertTrue(conflicts.get(0).getReason().contains("最低库存"),
                "原因须点明是哪两项打架，实际：" + conflicts.get(0).getReason());
    }

    /** 安全 > 最高 必须判为冲突。 */
    @Test
    void shouldRejectWhenSafeGreaterThanMax() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", inventory("10", "300", "200"));
        assertEquals(1, conflicts.size());
        assertEquals("inv.stock.safe", conflicts.get(0).getKey());
    }

    /** 负值必须判为冲突。 */
    @Test
    void shouldRejectNegativeInventoryValue() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", inventory("-1", "0", "200"));
        assertEquals(1, conflicts.size());
        assertEquals("inv.stock.min", conflicts.get(0).getKey());
    }

    /** 非数字必须判为冲突，且不能抛异常（校验器只负责「报」）。 */
    @Test
    void shouldRejectNonNumericInventoryValue() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", inventory("abc", "0", "200"));
        assertEquals(1, conflicts.size());
        assertEquals("inv.stock.min", conflicts.get(0).getKey());
    }

    /** 缺失项必须判为冲突（不能当作「不修改」而跳过——保存是全组提交）。 */
    @Test
    void shouldRejectWhenItemMissing() {
        Map<String, String> m = inventory("10", "50", "200");
        m.remove("inv.stock.safe");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", m);
        assertEquals(1, conflicts.size());
        assertEquals("inv.stock.safe", conflicts.get(0).getKey());
    }

    /** 越组写入必须判为冲突，防止前端把别的组的键混进来。 */
    @Test
    void shouldRejectKeyFromAnotherGroup() {
        Map<String, String> m = inventory("10", "50", "200");
        m.put("device.offline.threshold.seconds", "60");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", m);
        assertEquals(1, conflicts.size());
        assertEquals("device.offline.threshold.seconds", conflicts.get(0).getKey());
    }

    // ------------------------------------------------------------ 类型

    /** bool 只接受 true/false 两个值。 */
    @Test
    void shouldRejectNonBooleanValue() {
        Map<String, String> m = new HashMap<>();
        m.put("sync.scope", "库存账");
        m.put("sync.auto", "yes");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("sync", m);
        assertEquals(1, conflicts.size());
        assertEquals("sync.auto", conflicts.get(0).getKey());
    }

    /** 多选项中出现未声明的取值必须判为冲突。 */
    @Test
    void shouldRejectUnknownMultiOption() {
        Map<String, String> m = new HashMap<>();
        m.put("sync.auto", "true");
        m.put("sync.scope", "库存账,不存在的选项");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("sync", m);
        assertEquals(1, conflicts.size());
        assertEquals("sync.scope", conflicts.get(0).getKey());
    }

    /** 多选项为空必须判为冲突。 */
    @Test
    void shouldRejectEmptyMultiValue() {
        Map<String, String> m = new HashMap<>();
        m.put("sync.auto", "true");
        m.put("sync.scope", "  ");
        assertEquals(1, WmsConfigValidator.validate("sync", m).size());
    }

    /** 设备阈值必须 > 0。 */
    @Test
    void shouldRejectNonPositiveDeviceThreshold() {
        Map<String, String> m = new HashMap<>();
        m.put("device.offline.threshold.seconds", "0");
        m.put("device.alert.throttle.minutes", "30");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("device", m);
        assertEquals(1, conflicts.size());
        assertEquals("device.offline.threshold.seconds", conflicts.get(0).getKey());
    }

    /** 不良品默认仓允许为空（蓝图只要求「是否入线边柜」，默认仓是补充项）。 */
    @Test
    void shouldAcceptBlankDefectWarehouse() {
        Map<String, String> m = new HashMap<>();
        m.put("defect.bin.allowed", "false");
        m.put("defect.warehouse.code", "");
        assertTrue(WmsConfigValidator.validate("defect", m).isEmpty());
    }

    /** 不良品默认仓超长（>64，与库区编码同宽）必须判为冲突。 */
    @Test
    void shouldRejectOverlongDefectWarehouse() {
        Map<String, String> m = new HashMap<>();
        m.put("defect.bin.allowed", "false");
        m.put("defect.warehouse.code", "X".repeat(65));
        assertEquals(1, WmsConfigValidator.validate("defect", m).size());
    }

    /** 未知分组直接抛 IllegalArgumentException —— 调用方传错分组属于编程错误，不该伪装成业务冲突。 */
    @Test
    void shouldThrowOnUnknownGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsConfigValidator.validate("no-such-group", new HashMap<>()));
    }
}
