package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsSyncSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: SB-001 库存比对器：key 归一、BigDecimal 精度、四情形 matchStatus、ERP 单边行约束。
 *
 * <p>头号回归点：数量比较必须用 {@code compareTo}（1.0 与 1.00 相等），
 * 用 {@code equals} 会把精度差异的全部行误报成「数量差异」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncComparatorTest {

    @Test
    void key_formatsWarehouseMaterialBatch() {
        assertEquals("D.01|WA.001|L1", WmsSyncComparator.key("D.01", "WA.001", "L1"));
    }

    @Test
    void key_normalizesNullAndBlankBatchToSameKey() {
        String nullBatch = WmsSyncComparator.key("D.01", "WA.001", null);
        String emptyBatch = WmsSyncComparator.key("D.01", "WA.001", "");
        String blankBatch = WmsSyncComparator.key("D.01", "WA.001", "   ");

        assertEquals("D.01|WA.001|", nullBatch);
        assertEquals(nullBatch, emptyBatch);
        assertEquals(nullBatch, blankBatch);
    }

    @Test
    void apply_equalQuantitiesWithDifferentScale() {
        // Review Focus #1：1.0 vs 1.00 必须「一致」，不能被 scale 骗成数量差异
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "1.0");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "1.00");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_EQUAL, row.getMatchStatus());
        assertEquals(0, row.getErpQty().compareTo(new BigDecimal("1.00")));
        assertEquals(0, row.getDiffQty().compareTo(BigDecimal.ZERO));
    }

    @Test
    void apply_equalLargeScaleDifference() {
        WmsSyncSnapshot row = snapshot("D.02", "WA.002", null, "100.000000");
        Map<String, BigDecimal> erp = erp("D.02|WA.002|", "100");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_EQUAL, row.getMatchStatus());
        assertEquals(0, row.getDiffQty().compareTo(BigDecimal.ZERO));
    }

    @Test
    void apply_wmsGreaterThanErp_reportsQtyDiffWithPositiveDiff() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "10");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "8.5");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF, row.getMatchStatus());
        assertEquals(0, row.getDiffQty().compareTo(new BigDecimal("1.5")));
    }

    @Test
    void apply_wmsLessThanErp_reportsQtyDiffWithNegativeDiff() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "8");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "10");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF, row.getMatchStatus());
        assertEquals(0, row.getDiffQty().compareTo(new BigDecimal("-2")));
    }

    @Test
    void apply_wmsOnly_whenErpHasNoSuchKey() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "3");
        Map<String, BigDecimal> erp = erp("D.01|WA.999|L1", "7");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, row.getMatchStatus());
        assertEquals(0, row.getErpQty().compareTo(BigDecimal.ZERO));
        assertEquals(0, row.getDiffQty().compareTo(new BigDecimal("3")));
    }

    @Test
    void apply_batchNullMatchesErpEmptyBatch() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", null, "2");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|", "2");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_EQUAL, row.getMatchStatus());
    }

    @Test
    void apply_materialCodeBlank_forcesWmsOnlyAndSkipsErpLookup() {
        WmsSyncSnapshot row = snapshot("D.01", "", "L1", "6");
        // 即便 ERP 侧恰好有同形 key（"D.01||L1"），无金蝶编码的物料也不得参与匹配
        Map<String, BigDecimal> erp = erp("D.01||L1", "6");

        WmsSyncComparator.apply(list(row), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, row.getMatchStatus());
        assertNull(row.getErpQty(), "未参与 ERP 匹配的行不应编造 ERP 数量");
        assertNull(row.getDiffQty());
    }

    @Test
    void apply_materialCodeNull_forcesWmsOnly() {
        WmsSyncSnapshot row = snapshot("D.01", null, null, "6");

        WmsSyncComparator.apply(list(row), erp("D.01||", "6"), false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, row.getMatchStatus());
        assertNull(row.getErpQty());
    }

    @Test
    void apply_erpOnlyRow_markedByCallerStaysErpOnly() {
        // ERP 单边行由调用方构造：wmsQty=0，且预先标记 matchStatus=仅ERP
        WmsSyncSnapshot erpOnly = snapshot("D.01", "WA.001", "L1", "0")
                .setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY);
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "5");

        WmsSyncComparator.apply(list(erpOnly), erp, true);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY, erpOnly.getMatchStatus());
        assertEquals(0, erpOnly.getErpQty().compareTo(new BigDecimal("5")));
        assertEquals(0, erpOnly.getDiffQty().compareTo(new BigDecimal("-5")));
    }

    @Test
    void apply_erpOnlyMarkerIgnoredWhenDetectErpOnlyDisabled() {
        // javadoc 约束：detectErpOnly=false 时调用方不得塞 ERP 单边行；万一塞了也不能被当成「仅ERP」
        WmsSyncSnapshot fakeErpOnly = snapshot("D.01", "WA.001", "L1", "0")
                .setMatchStatus(WmsConstants.SNAPSHOT_MATCH_ERP_ONLY);
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "5");

        WmsSyncComparator.apply(list(fakeErpOnly), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF, fakeErpOnly.getMatchStatus());
    }

    @Test
    void apply_extraErpKeys_doNotCreateRows() {
        // detectErpOnly=false（TODAY 口径）时，ERP 多出来的 key 不得产生任何行
        List<WmsSyncSnapshot> snapshots = new ArrayList<>();
        snapshots.add(snapshot("D.01", "WA.001", "L1", "1"));
        snapshots.add(snapshot("D.01", "WA.002", "L1", "2"));
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "1");
        erp.put("D.01|WA.003|L1", new BigDecimal("9"));

        WmsSyncComparator.apply(snapshots, erp, false);

        assertEquals(2, snapshots.size());
        for (WmsSyncSnapshot row : snapshots) {
            assertTrue(!WmsConstants.SNAPSHOT_MATCH_ERP_ONLY.equals(row.getMatchStatus()));
        }
    }

    @Test
    void apply_nullErpMap_marksAllRowsWmsOnly() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "4");

        WmsSyncComparator.apply(list(row), null, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, row.getMatchStatus());
    }

    @Test
    void apply_nullSnapshotList_isNoOp() {
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "1");

        WmsSyncComparator.apply(null, erp, true);

        assertTrue(erp.containsKey("D.01|WA.001|L1"), "比对器不得改动入参 ERP map");
    }

    @Test
    void apply_doesNotMutateErpMap() {
        WmsSyncSnapshot row = snapshot("D.01", "WA.001", "L1", "1");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "1");
        Map<String, BigDecimal> before = new HashMap<>(erp);

        WmsSyncComparator.apply(list(row), erp, true);

        assertEquals(before, erp);
    }

    @Test
    void apply_multipleRows_eachMatchedIndependently() {
        WmsSyncSnapshot equal = snapshot("D.01", "WA.001", "L1", "1.0");
        WmsSyncSnapshot diff = snapshot("D.01", "WA.002", "L1", "2");
        WmsSyncSnapshot wmsOnly = snapshot("D.01", "WA.003", "L1", "3");
        WmsSyncSnapshot blankMaterial = snapshot("D.01", "", "L1", "4");
        Map<String, BigDecimal> erp = erp("D.01|WA.001|L1", "1.00");
        erp.put("D.01|WA.002|L1", new BigDecimal("5"));
        erp.put("D.01||L1", new BigDecimal("4"));

        WmsSyncComparator.apply(list(equal, diff, wmsOnly, blankMaterial), erp, false);

        assertEquals(WmsConstants.SNAPSHOT_MATCH_EQUAL, equal.getMatchStatus());
        assertEquals(WmsConstants.SNAPSHOT_MATCH_QTY_DIFF, diff.getMatchStatus());
        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, wmsOnly.getMatchStatus());
        assertEquals(WmsConstants.SNAPSHOT_MATCH_WMS_ONLY, blankMaterial.getMatchStatus());
    }

    // ------------------------------------------------------------------ helpers

    private static WmsSyncSnapshot snapshot(String warehouse, String material, String batch, String wmsQty) {
        return new WmsSyncSnapshot()
                .setWarehouseCode(warehouse)
                .setMaterialCode(material)
                .setBatchNo(batch)
                .setWmsQty(wmsQty == null ? null : new BigDecimal(wmsQty));
    }

    private static Map<String, BigDecimal> erp(String key, String qty) {
        Map<String, BigDecimal> map = new HashMap<>();
        map.put(key, new BigDecimal(qty));
        return map;
    }

    private static List<WmsSyncSnapshot> list(WmsSyncSnapshot... rows) {
        List<WmsSyncSnapshot> list = new ArrayList<>();
        for (WmsSyncSnapshot row : rows) {
            list.add(row);
        }
        return list;
    }
}
