package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @Description: SB-001 库存比对器（纯静态工具类，无 Spring / 无数据库依赖）。
 *
 * <p>按 spec §2.4 把金蝶即时库存（{@code Map<key, qty>}）与 WMS 快照行比对，
 * 就地回填每行的 {@code erpQty / diffQty / matchStatus}。四情形：
 *
 * <table border="1">
 *   <caption>比对规则</caption>
 *   <tr><th>情形</th><th>matchStatus</th><th>字段回填</th></tr>
 *   <tr><td>两边都有且 {@code compareTo == 0}</td><td>一致</td><td>erpQty、diffQty=0</td></tr>
 *   <tr><td>两边都有但数量不等</td><td>数量差异</td><td>erpQty、diffQty=wms-erp（多账为正、少账为负）</td></tr>
 *   <tr><td>仅 WMS 有（ERP key 不存在 / 无金蝶编码）</td><td>仅WMS</td><td>见下</td></tr>
 *   <tr><td>仅 ERP 有（调用方构造的单边行）</td><td>仅ERP</td><td>erpQty、diffQty=-erpQty</td></tr>
 * </table>
 *
 * <p><b>数量比较只用 {@link BigDecimal#compareTo}</b>：{@code 1.0} 与 {@code 1.00} 必须判「一致」，
 * 用 {@code equals} 会把全部精度差异误报成「数量差异」——本类头号回归点。
 *
 * <p><b>key 口径</b>：{@code 仓库|物料|批号}，与 Task 5 的 {@code IWmsKingdeeInventoryReader}
 * 逐字一致（各段 null/空白归一为 {@code ""}，并去首尾空白），否则同一个库存会在两边被拆成两个 key，
 * 产出「仅WMS + 仅ERP」的伪差异。
 *
 * <p><b>ERP 单边行由调用方构造</b>（本类不造行，spec §2.4）：
 * 调用方扫 {@code erpByKey} 中不在快照里的 key，构造 {@code wmsQty=0} 的快照行，
 * 并<b>预先置 {@code matchStatus=仅ERP}</b> 后再传入 {@link #apply}——这是本类识别「单边行」的唯一标记，
 * 不能靠 {@code wmsQty==0} 判断（WMS 真实零库存行也满足该条件，会被误判）。
 *
 * <p><b>{@code detectErpOnly=false} 时调用方不得把 ERP 单边行放进 snapshots</b>（TODAY 口径，
 * spec §2.4 不产生「仅ERP」）。即使塞了，本类也不会认标记，会按普通行算出「数量差异 / 一致」，
 * 从而在测试里暴露调用方越界。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
final class WmsSyncComparator {

    /** key 分隔符，与 {@code WmsKingdeeInventoryReaderImpl} 一致。 */
    static final String KEY_SEPARATOR = "|";

    private WmsSyncComparator() {
    }

    /**
     * 拼比对 key：{@code 仓库|物料|批号}。
     *
     * <p>null / 空串 / 纯空白一律归一为 {@code ""}（批号为空时 key 以 {@code |} 结尾），
     * 与金蝶侧 Reader 和 WMS 侧 {@code COALESCE(batch_no,'')} 聚合口径保持一致。
     */
    static String key(String warehouseCode, String materialCode, String batchNo) {
        return text(warehouseCode) + KEY_SEPARATOR + text(materialCode) + KEY_SEPARATOR + text(batchNo);
    }

    /**
     * 就地回填快照行。
     *
     * @param snapshots     WMS 侧快照行（{@code detectErpOnly=true} 时可含调用方构造的 ERP 单边行）
     * @param erpByKey      金蝶即时库存，key 见 {@link #key}；null 视为空表
     * @param detectErpOnly 是否认调用方预置的「仅ERP」标记；TODAY 口径传 false
     */
    static void apply(List<WmsSyncSnapshot> snapshots, Map<String, BigDecimal> erpByKey, boolean detectErpOnly) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> erp = erpByKey == null ? Collections.emptyMap() : erpByKey;
        for (WmsSyncSnapshot row : snapshots) {
            if (row != null) {
                fillRow(row, erp, detectErpOnly);
            }
        }
    }

    private static void fillRow(WmsSyncSnapshot row, Map<String, BigDecimal> erpByKey, boolean detectErpOnly) {
        BigDecimal wmsQty = row.getWmsQty() == null ? BigDecimal.ZERO : row.getWmsQty();

        if (text(row.getMaterialCode()).isEmpty()) {
            // 无金蝶编码的非 ERP 物料：不参与 ERP 匹配，也不编造 ERP 数量（spec §2.1 / §2.4）
            row.setErpQty(null);
            row.setDiffQty(null);
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY);
            return;
        }

        String key = key(row.getWarehouseCode(), row.getMaterialCode(), row.getBatchNo());
        BigDecimal erpQty = erpByKey.get(key);
        if (erpQty == null) {
            // 仅 WMS：ERP 侧无该 key，数量记 0，差额即 WMS 数量
            row.setErpQty(BigDecimal.ZERO);
            row.setDiffQty(wmsQty);
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY);
            return;
        }

        row.setErpQty(erpQty);
        row.setDiffQty(wmsQty.subtract(erpQty));

        if (detectErpOnly && WmsConstants.SNAPSHOT_MATCH_ERP_ONLY.equals(row.getMatchStatus())) {
            // 调用方预置的 ERP 单边行（wmsQty=0）：保留「仅ERP」，不得降级成「数量差异」
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY);
        } else if (wmsQty.compareTo(erpQty) == 0) {
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_EQUAL);
        } else {
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF);
        }
    }

    /** 文本归一：null → ""，并去掉首尾空白（批号 null / 空串 / 纯空白因此落到同一 key）。 */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
