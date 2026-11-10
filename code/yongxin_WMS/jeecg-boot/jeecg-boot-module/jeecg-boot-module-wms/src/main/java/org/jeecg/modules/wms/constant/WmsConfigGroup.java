package org.jeecg.modules.wms.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 系统配置分组（蓝图 YX-WMS-BD-004 的步骤 3~6）。
 *
 * <p>顺序即前端左侧菜单顺序，故显式给出 {@link #ordered()} / {@link #orderedAsList()}，
 * 不依赖声明顺序的偶然性。
 *
 * @author 永鑫WMS
 */
public enum WmsConfigGroup {

    INVENTORY("inventory", "库存预警阈值"),
    SYNC("sync", "上位系统同步范围"),
    DEFECT("defect", "不良品存放规则"),
    DEVICE("device", "设备告警阈值"),
    AUDIT("audit", "审计留痕"),
    /**
     * 金蝶单据 formId（V2.4 spec §6）。
     *
     * <p>formId 由 BOS 控制台确认后由管理员录入；单独成组是为了让「未配置」在配置页一眼可见，
     * 不必翻到同步组里逐项找。
     */
    KINGDEE("kingdee", "金蝶单据"),
    /** 单据打印（DY-001）：抬头公司名与落款公司信息块。 */
    PRINT("print", "单据打印");

    private final String code;
    private final String name;

    WmsConfigGroup(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /** 按 code 取分组；未知 code 直接拒绝，避免静默落到某个默认分组。 */
    public static WmsConfigGroup of(String code) {
        for (WmsConfigGroup group : values()) {
            if (group.code.equals(code)) {
                return group;
            }
        }
        throw new IllegalArgumentException("未知配置分组：" + code);
    }

    /** 前端菜单顺序。 */
    public static List<WmsConfigGroup> orderedAsList() {
        return Arrays.asList(INVENTORY, SYNC, DEFECT, DEVICE, AUDIT, KINGDEE);
    }

    public static WmsConfigGroup[] ordered() {
        return orderedAsList().toArray(new WmsConfigGroup[0]);
    }
}
