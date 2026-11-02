package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 审计留痕常量守卫。
 *
 * <p>「通道」是审计表最容易被随意写字符串的字段——写错一个字，事后按通道统计就漏。
 * 这里把设计文档 §5 列的五种通道钉成常量，任何新增通道必须先加常量再用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsAuditConstantsTest {

    private static Set<String> stringConstants(String prefix) {
        return Arrays.stream(WmsConstants.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .filter(f -> f.getName().startsWith(prefix))
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .collect(Collectors.toSet());
    }

    @Test
    void channels_coverAllFiveFromDesign() {
        Set<String> channels = stringConstants("AUDIT_CHANNEL_");

        assertEquals(Set.of("界面操作", "ERP同步", "回写ERP", "设备回调", "定时任务"), channels,
                "审计通道必须与设计文档 §5 的 5 种一致，实际：" + channels);
    }

    @Test
    void results_areExactlySuccessOrFailed() {
        assertEquals(Set.of("成功", "失败"), stringConstants("AUDIT_RESULT_"));
    }

    /** 业务类型：与设计文档 §4 的 biz_type 注释一致。用「包含」而非「等于」——后续任务允许扩展。 */
    @Test
    void bizTypes_coverSpecSection4() {
        Set<String> types = stringConstants("AUDIT_BIZ_");
        assertTrue(types.containsAll(
                Set.of("工单", "入库单", "出库单", "调拨单", "盘点单", "系统配置", "主数据")),
                "缺少设计文档 §4 列的业务类型，实际：" + types);
    }

    /** 动作：与设计文档 §4 的 action 注释一致。同样只要求包含。 */
    @Test
    void actions_coverSpecSection4() {
        Set<String> actions = stringConstants("AUDIT_ACTION_");
        assertTrue(actions.containsAll(
                Set.of("创建", "修改", "审核", "作废", "同步落地", "回写ERP", "绑定", "解绑")),
                "缺少设计文档 §4 列的动作，实际：" + actions);
    }

    @Test
    void systemOperatorConstantExists() {
        assertTrue(stringConstants("AUDIT_OPERATOR_").contains("system"),
                "定时任务/回写没有登录用户，operator 必须落成 system，不能留空");
    }
}
