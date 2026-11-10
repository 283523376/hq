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
 *   <tr><td>仅 WMS 有（ERP key 不存在 / 无金蝶编码）</td><td>仅WMS</td><td>erpQty=0、diffQty=wmsQty</td></tr>
 *   <tr><td>仅 ERP 有（调用方构造的单边行）</td><td>仅ERP</td><td>wmsQty=0、erpQty=erp、diffQty=-erp</td></tr>
 * </table>
 *
 * <p><b>数量口径统一为 0，不留 null</b>：{@code wms_sync_diff.diff_qty} 是 {@code NOT NULL}，
 * 任何「仅WMS/仅ERP」行若 diffQty 为 null，Task 7 落差异表会直接失败（2026-11-03 裁决）。
 *
 * <p><b>数量比较只用 {@link BigDecimal#compareTo}</b>：{@code 1.0} 与 {@code 1.00} 必须判「一致」，
 * 用 {@code equals} 会把全部精度差异误报成「数量差异」——本类头号回归点。
 *
 * <p><b>key 口径</b>：{@code 仓库|物料|批号}，与 Task 5 的 {@code IWmsKingdeeInventoryReader}
 * 逐字一致（各段 null/空白归一为 {@code ""}，并去首尾空白），否则同一个库存会在两边被拆成两个 key，
 * 产出「仅WMS + 仅ERP」的伪差异。
 *
 * <p><b>ERP 单边行由调用方构造</b>（本类不造行，spec §2.4）。调用方扫 {@code erpByKey} 中不在快照里的 key，
 * 构造快照行时<b>必须同时满足两个条件</b>：
 * <ol>
 *   <li>{@code setWmsQty(BigDecimal.ZERO)}——WMS 侧无该库存行；</li>
 *   <li>{@code setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY)}——本类识别「单边行」的<b>唯一</b>标记。</li>
 * </ol>
 * 不能靠 {@code wmsQty==0} 判断（WMS 真实零库存行也满足该条件，会被误判成「仅ERP」）。
 *
 * <p><b>契约 fail-fast</b>（违反即 {@link IllegalArgumentException}，不静默降级）：
 * <ul>
 *   <li>{@code detectErpOnly=false}（TODAY 口径，spec §2.4 不产生「仅ERP」）却传入预置「仅ERP」的行；</li>
 *   <li>{@code detectErpOnly=true} 但预置「仅ERP」行的 key 在 {@code erpByKey} 里找不到（调用方拼错 key）；</li>
 *   <li>预置「仅ERP」行缺物料编码（无法构造 ERP key）。</li>
 * </ul>
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
     * @throws IllegalArgumentException 违反 ERP 单边行契约（见类注释「契约 fail-fast」）
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
        if (WmsConstants.SNAPSHOT_MATCH_ERP_ONLY.equals(row.getMatchStatus())) {
            fillErpOnlyRow(row, erpByKey, detectErpOnly);
            return;
        }

        BigDecimal wmsQty = row.getWmsQty() == null ? BigDecimal.ZERO : row.getWmsQty();

        if (text(row.getMaterialCode()).isEmpty()) {
            // 无金蝶编码的非 ERP 物料：不参与 ERP 匹配；数量口径统一记 0，不留 null
            row.setErpQty(BigDecimal.ZERO);
            row.setDiffQty(wmsQty);
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
        if (wmsQty.compareTo(erpQty) == 0) {
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_EQUAL);
        } else {
            row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF);
        }
    }

    /**
     * ERP 单边行：调用方必须预置 {@code matchStatus=仅ERP} 且 {@code wmsQty=0}（本方法再次兜底置 0）。
     * 违反契约直接抛 {@link IllegalArgumentException}，避免静默产出错误差异。
     */
    private static void fillErpOnlyRow(WmsSyncSnapshot row, Map<String, BigDecimal> erpByKey, boolean detectErpOnly) {
        String key = key(row.getWarehouseCode(), row.getMaterialCode(), row.getBatchNo());
        if (!detectErpOnly) {
            throw new IllegalArgumentException(
                    "detectErpOnly=false 时不得传入预置「仅ERP」的快照行（TODAY 口径不产生仅ERP），key=" + key);
        }
        if (text(row.getMaterialCode()).isEmpty()) {
            throw new IllegalArgumentException("ERP 单边行缺少物料编码，无法构造比对 key：" + key);
        }
        BigDecimal erpQty = erpByKey.get(key);
        if (erpQty == null) {
            throw new IllegalArgumentException("ERP 单边行的 key 在本次金蝶即时库存中不存在，疑似调用方拼错 key：" + key);
        }
        row.setWmsQty(BigDecimal.ZERO);
        row.setErpQty(erpQty);
        row.setDiffQty(erpQty.negate());
        row.setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY);
    }

    /** 文本归一：null → ""，并去掉首尾空白（批号 null / 空串 / 纯空白因此落到同一 key）。 */
    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
