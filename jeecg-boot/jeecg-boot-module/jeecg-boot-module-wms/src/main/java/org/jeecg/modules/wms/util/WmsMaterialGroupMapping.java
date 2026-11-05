package org.jeecg.modules.wms.util;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.wms.constant.WmsConstants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶物料分组 → WMS 分类类型映射串解析（纯函数，可穷举单测）。
 *
 * <p>配置值来自系统配置 {@code kingdee.material-group-map}，格式：
 * <pre>分组编码=分类类型;分组编码=分类类型</pre>
 * 分类类型只允许 {@link WmsConstants#CATEGORY_TYPE_RAW 原材料}、
 * {@link WmsConstants#CATEGORY_TYPE_HALF_FINISHED 半成品}、
 * {@link WmsConstants#CATEGORY_TYPE_PRODUCT 成品}、
 * {@link WmsConstants#CATEGORY_TYPE_AUXILIARY 辅料}。
 *
 * <p><b>解析失败整串忽略</b>：只要有任何一段格式错或类型非法，就返回空映射并打 WARN。
 * 部分生效比全部不生效更难排查（用户会看到「有的分类挂上了、有的没挂」），
 * 而空映射的后果只是分类类型留空，不会阻断同步。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-05
 */
@Slf4j
public final class WmsMaterialGroupMapping {

    /** 条目分隔符（兼容中文分号，管理员从文档里粘过来很常见）。 */
    private static final String ENTRY_SEPARATOR_REGEX = "[;；]";

    /** 键值分隔符。 */
    private static final char KEY_VALUE_SEPARATOR = '=';

    /** 允许的分类类型（顺序即报错文案里的展示顺序）。 */
    public static final List<String> ALLOWED_TYPES = List.of(
            WmsConstants.CATEGORY_TYPE_RAW,
            WmsConstants.CATEGORY_TYPE_HALF_FINISHED,
            WmsConstants.CATEGORY_TYPE_PRODUCT,
            WmsConstants.CATEGORY_TYPE_AUXILIARY);

    private WmsMaterialGroupMapping() {
    }

    /**
     * 解析映射串。
     *
     * @param raw 配置原文；null/空白 → 空映射（合法初始态，不告警）
     * @return 分组编码 → 分类类型；任何一条非法时整体返回空映射
     */
    public static Map<String, String> parse(String raw) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return mapping;
        }
        for (String segment : raw.split(ENTRY_SEPARATOR_REGEX, -1)) {
            String entry = segment == null ? "" : segment.trim();
            if (entry.isEmpty()) {
                return invalid(raw, "存在空条目（多为多打了分号）");
            }
            int idx = entry.indexOf(KEY_VALUE_SEPARATOR);
            if (idx <= 0 || idx == entry.length() - 1) {
                return invalid(raw, "条目缺少「=」或键/值为空：" + entry);
            }
            String groupNumber = entry.substring(0, idx).trim();
            String categoryType = entry.substring(idx + 1).trim();
            if (groupNumber.isEmpty() || categoryType.isEmpty()) {
                return invalid(raw, "条目缺少「=」或键/值为空：" + entry);
            }
            if (!ALLOWED_TYPES.contains(categoryType)) {
                return invalid(raw, "分类类型非法（允许：" + String.join("/", ALLOWED_TYPES)
                        + "）：" + entry);
            }
            mapping.put(groupNumber, categoryType);
        }
        return mapping;
    }

    /** 任一非法条目都让整串失效：日志里带原文，方便直接改配置。 */
    private static Map<String, String> invalid(String raw, String reason) {
        log.warn("金蝶物料分组映射配置解析失败（整串忽略）：{}；原文：{}", reason, raw);
        return new LinkedHashMap<>();
    }
}
