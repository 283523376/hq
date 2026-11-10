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

    /** SYNC 组全组合法值（校验器按「保存 = 全组提交」检查缺项，夹具必须放满组内全部 key）。 */
    private static Map<String, String> syncGroup() {
        Map<String, String> m = new HashMap<>();
        m.put("sync.scope", WmsKingdeeSyncScope.labels().get(0));
        m.put("sync.auto", "true");
        m.put("sync.auto.scope", WmsConfigKey.SYNC_AUTO_SCOPE.getDefaultValue());
        m.put("sync.log.retention.days", WmsConfigKey.SYNC_LOG_RETENTION_DAYS.getDefaultValue());
        m.put("sync.retention.days", WmsConfigKey.SYNC_RETENTION_DAYS.getDefaultValue());
        m.put("sync.retention.diff-days", WmsConfigKey.SYNC_RETENTION_DIFF_DAYS.getDefaultValue());
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
        Map<String, String> m = syncGroup();
        m.put("sync.auto", "yes");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("sync", m);
        assertEquals(1, conflicts.size());
        assertEquals("sync.auto", conflicts.get(0).getKey());
    }

    /** 多选项中出现未声明的取值必须判为冲突。 */
    @Test
    void shouldRejectUnknownMultiOption() {
        Map<String, String> m = syncGroup();
        m.put("sync.scope", WmsKingdeeSyncScope.labels().get(0) + ",不存在的选项");
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("sync", m);
        assertEquals(1, conflicts.size());
        assertEquals("sync.scope", conflicts.get(0).getKey());
    }

    /** 多选项为空必须判为冲突。 */
    @Test
    void shouldRejectEmptyMultiValue() {
        Map<String, String> m = syncGroup();
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

    /** 不良品默认仓允许为空：留空表示沿用废品仓默认库位。 */
    @Test
    void shouldAcceptBlankDefectWarehouse() {
        Map<String, String> m = new HashMap<>();
        m.put("defect.warehouse.code", "");
        assertTrue(WmsConfigValidator.validate("defect", m).isEmpty());
    }

    /** 不良品默认仓超长（>64，与库区编码同宽）必须判为冲突。 */
    @Test
    void shouldRejectOverlongDefectWarehouse() {
        Map<String, String> m = new HashMap<>();
        m.put("defect.warehouse.code", "X".repeat(65));
        assertEquals(1, WmsConfigValidator.validate("defect", m).size());
    }

    /** 未知分组直接抛 IllegalArgumentException —— 调用方传错分组属于编程错误，不该伪装成业务冲突。 */
    @Test
    void shouldThrowOnUnknownGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsConfigValidator.validate("no-such-group", new HashMap<>()));
    }

    // ------------------------------------------------------------ 整数宽度（评审 I1 / I3）

    /**
     * 超长数字必须报成**普通冲突**，不得抛 NumberFormatException。
     *
     * <p>UI 的 a-input-number 没有上界，管理员敲 20 位数字完全可达；而校验器自称「只报不抛」，
     * 一旦抛异常就会绕过「逐字冲突文案 + 明细」这条路径。
     */
    @Test
    void shouldRejectHugeNumberAsConflictNotException() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory",
                inventory("100000000000000000000", "50", "200"));
        assertEquals(1, conflicts.size(), "必须是普通冲突，而不是异常");
        assertEquals("inv.stock.min", conflicts.get(0).getKey());
    }

    /**
     * 超出 int 范围的值必须被拒：否则「存得进、读不出」——
     * 校验按 long 放行、一致性检查与 getInt 按 int 溢出，规则会漏判。
     */
    @Test
    void shouldRejectValueAboveIntRange() {
        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory",
                inventory("3000000000", "5", "10"));
        assertEquals(1, conflicts.size(), "3000000000 超出 int 范围，必须判冲突");
        assertEquals("inv.stock.min", conflicts.get(0).getKey());
    }

    /** int 上界内的极值仍应通过（别把上限设得比 int 还小）。 */
    @Test
    void shouldAcceptMaxIntValue() {
        assertTrue(WmsConfigValidator.validate("inventory",
                inventory("2147483647", "2147483647", "2147483647")).isEmpty());
    }

    /** 设备阈值同样受 int 上界约束（否则页面显示 30 亿、运行时回落 600）。 */
    @Test
    void shouldRejectDeviceThresholdAboveIntRange() {
        Map<String, String> m = new HashMap<>();
        m.put("device.offline.threshold.seconds", "3000000000");
        m.put("device.alert.throttle.minutes", "30");
        assertEquals(1, WmsConfigValidator.validate("device", m).size());
    }

    // ------------------------------------------------------------ 遗留 Minor（评审 M3 / M6）

    /**
     * 枚举里**根本不存在**的键也必须报成冲突，而不是抛 IllegalArgumentException（M3）。
     *
     * <p>它是用户输入的一部分（手工构造请求可达），抛异常会绕过「逐字冲突文案 + 明细」。
     */
    @Test
    void shouldReportUnknownKeyAsConflictNotException() {
        Map<String, String> m = inventory("10", "50", "200");
        m.put("foo", "1");

        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("inventory", m);

        assertEquals(1, conflicts.size(), "未知键必须是普通冲突，不是异常");
        assertEquals("foo", conflicts.get(0).getKey());
        assertTrue(conflicts.get(0).getReason().contains("未知配置项"), "实际：" + conflicts.get(0).getReason());
    }

    /** 多选项里的空段（多打一个逗号）必须判冲突——它几乎总是手误，静默丢弃会存下与页面不一致的值（M6）。 */
    @Test
    void shouldRejectEmptyMultiSegment() {
        Map<String, String> m = syncGroup();
        m.put("sync.scope", WmsKingdeeSyncScope.labels().get(0) + ",," + WmsKingdeeSyncScope.labels().get(1));

        List<WmsConfigConflict> conflicts = WmsConfigValidator.validate("sync", m);

        assertEquals(1, conflicts.size(), "空段必须判冲突");
        assertEquals("sync.scope", conflicts.get(0).getKey());
    }

    /** 段内前后空格不算错（服务层会规范化后落库），但整串只有逗号必须拒。 */
    @Test
    void shouldRejectMultiValueMadeOfCommasOnly() {
        Map<String, String> m = syncGroup();
        m.put("sync.scope", ",,,");
        assertEquals(1, WmsConfigValidator.validate("sync", m).size());
    }
}
