package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @Description: 盘点单状态机——白名单转移，任何未列出的转移一律拒绝。
 *
 * <p>合法链（含复核回退）：
 * <pre>
 *   草稿 → 盘点中
 *   盘点中 → 差异确认         （阈值内）
 *   盘点中 → 复核中           （超阈值直达）
 *   差异确认 → 复核中
 *   复核中 → 差异确认         （复核通过，回退给调整环节）
 *   差异确认 → 已调整
 *   已调整 → 已归档           （终态）
 * </pre>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
@Component
public class WmsStocktakeStatusResolver {

    private static final Map<String, Set<String>> TRANSITIONS = buildTransitions();
    private static final Set<String> TERMINAL = Set.of(WmsConstants.STOCKTAKE_STATUS_ARCHIVED);

    public void assertTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new RuntimeException("盘点单状态不允许从 " + from + " 变更为 " + to);
        }
    }

    public boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }

    private static Map<String, Set<String>> buildTransitions() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put(WmsConstants.STOCKTAKE_STATUS_DRAFT,
                Set.of(WmsConstants.STOCKTAKE_STATUS_COUNTING));
        map.put(WmsConstants.STOCKTAKE_STATUS_COUNTING,
                new HashSet<>(Set.of(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED,
                        WmsConstants.STOCKTAKE_STATUS_REVIEWING)));
        map.put(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED,
                new HashSet<>(Set.of(WmsConstants.STOCKTAKE_STATUS_REVIEWING,
                        WmsConstants.STOCKTAKE_STATUS_ADJUSTED)));
        map.put(WmsConstants.STOCKTAKE_STATUS_REVIEWING,
                Set.of(WmsConstants.STOCKTAKE_STATUS_DIFF_CONFIRMED));
        map.put(WmsConstants.STOCKTAKE_STATUS_ADJUSTED,
                Set.of(WmsConstants.STOCKTAKE_STATUS_ARCHIVED));
        return map;
    }
}
