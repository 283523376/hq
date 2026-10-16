package org.jeecg.modules.wms.constant;

import org.jeecg.modules.wms.vo.WmsConfigConflict;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配置校验器（**纯函数**，不依赖 Spring、不碰数据库，故可穷举测试）。
 *
 * <p>校验三件事，缺一不可：
 * <ol>
 *   <li><b>归属</b>：提交的键必须属于所声明的分组（否则前端能把别的组的值写进来）；</li>
 *   <li><b>类型与值域</b>：按 {@link WmsConfigKey#getType()} 解析，再按项判值域；</li>
 *   <li><b>组内自洽</b>：库存三项必须满足 最低 ≤ 安全 ≤ 最高。</li>
 * </ol>
 *
 * <p>只「报」不「抛」业务冲突（返回列表）；只有调用方传了未知分组这种**编程错误**才抛
 * {@link IllegalArgumentException} —— 把它伪装成业务冲突会让真 bug 混在用户输入里。
 *
 * @author 永鑫WMS
 */
public final class WmsConfigValidator {

    /** 不良品默认仓的编码长度上限，与库区编码同宽（location_code 列宽 64）。 */
    public static final int CODE_MAX_LENGTH = 64;

    private WmsConfigValidator() {
    }

    /**
     * 校验某分组的全部配置值。
     *
     * @param groupCode 分组 code
     * @param values    该分组的全部键值（**全组提交**，缺项即冲突）
     * @return 冲突列表；空列表表示通过
     */
    public static List<WmsConfigConflict> validate(String groupCode, Map<String, String> values) {
        WmsConfigGroup group = WmsConfigGroup.of(groupCode);
        Map<String, String> safeValues = values == null ? Map.of() : values;
        List<WmsConfigConflict> conflicts = new ArrayList<>();

        // 1) 越组写入：提交里出现了不属于本组的键
        Set<String> groupKeys = new HashSet<>();
        for (WmsConfigKey item : WmsConfigKey.ofGroup(group)) {
            groupKeys.add(item.getKey());
        }
        for (String submitted : safeValues.keySet()) {
            if (!groupKeys.contains(submitted)) {
                WmsConfigKey foreign = WmsConfigKey.of(submitted);
                conflicts.add(new WmsConfigConflict(submitted, foreign.getLabel(),
                        "配置项不属于分组「" + group.getName() + "」"));
            }
        }

        // 2) 逐项：缺项 / 类型 / 值域
        for (WmsConfigKey item : WmsConfigKey.ofGroup(group)) {
            if (!safeValues.containsKey(item.getKey())) {
                conflicts.add(new WmsConfigConflict(item.getKey(), item.getLabel(), "配置项缺失，保存需提交整组"));
                continue;
            }
            String reason = checkValue(item, safeValues.get(item.getKey()));
            if (reason != null) {
                conflicts.add(new WmsConfigConflict(item.getKey(), item.getLabel(), reason));
            }
        }

        // 3) 组内自洽（只在逐项都过时才判，避免与类型错误重复报同一项）
        if (group == WmsConfigGroup.INVENTORY && conflicts.isEmpty()) {
            int min = parseInt(safeValues.get(WmsConfigKey.INV_STOCK_MIN.getKey()));
            int safe = parseInt(safeValues.get(WmsConfigKey.INV_STOCK_SAFE.getKey()));
            int max = parseInt(safeValues.get(WmsConfigKey.INV_STOCK_MAX.getKey()));
            if (min > safe) {
                conflicts.add(new WmsConfigConflict(WmsConfigKey.INV_STOCK_MIN.getKey(),
                        WmsConfigKey.INV_STOCK_MIN.getLabel(),
                        "最低库存(" + min + ") 不能大于 安全库存(" + safe + ")"));
            } else if (safe > max) {
                conflicts.add(new WmsConfigConflict(WmsConfigKey.INV_STOCK_SAFE.getKey(),
                        WmsConfigKey.INV_STOCK_SAFE.getLabel(),
                        "安全库存(" + safe + ") 不能大于 最高库存(" + max + ")"));
            }
        }
        return conflicts;
    }

    /** 单值检查；返回 null 表示通过。 */
    private static String checkValue(WmsConfigKey item, String raw) {
        String value = raw == null ? "" : raw.trim();
        switch (item.getType()) {
            case INT: {
                if (!value.matches("-?\\d+")) {
                    return "必须为整数，实际：" + (raw == null ? "(空)" : raw);
                }
                long parsed = Long.parseLong(value);
                if (item.getGroup() == WmsConfigGroup.INVENTORY && parsed < 0) {
                    return "不能为负数，实际：" + value;
                }
                if (item.getGroup() == WmsConfigGroup.DEVICE && parsed <= 0) {
                    return "必须大于 0，实际：" + value;
                }
                return null;
            }
            case BOOL:
                return "true".equals(value) || "false".equals(value) ? null : "必须为 true 或 false，实际：" + value;
            case MULTI: {
                if (value.isEmpty()) {
                    return "不能为空";
                }
                List<String> allowed = item.getOptions();
                for (String part : value.split(",")) {
                    String option = part.trim();
                    if (option.isEmpty()) {
                        continue;
                    }
                    if (allowed == null || !allowed.contains(option)) {
                        return "取值不在候选范围（" + String.join("/", allowed == null ? List.<String>of() : allowed)
                                + "），实际：" + option;
                    }
                }
                return null;
            }
            case TEXT: {
                if (item == WmsConfigKey.DEFECT_WAREHOUSE_CODE && value.length() > CODE_MAX_LENGTH) {
                    return "长度不能超过 " + CODE_MAX_LENGTH + " 字符，实际：" + value.length();
                }
                return null;
            }
            default:
                return null;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
