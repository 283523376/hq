package org.jeecg.modules.wms.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Description: 金蝶即时库存（STK_Inventory）读取实现。
 *
 * <p>字段实测（spec §2.2）：{@code FMaterialId.FNumber}、{@code FStockId.FNumber}、
 * {@code FLot.FNumber}、{@code FBaseQty}；组织固定 101（永鑫宜昌）。
 *
 * <p><b>翻页</b>：{@link WmsKingdeeFormClient} 会把 {@code TopRowCount} 置 0 并用 {@code Limit}
 * 翻页——{@code TopRowCount} 会先截断结果集，StartRow 就永远取不到第二页。每页 500，
 * 返回行数 &lt; 500 即结束。
 *
 * <p><b>key 口径</b>：{@code 仓库|物料|批号}，批号 null/空串统一归一为 {@code ""}，与 WMS 侧
 * {@code COALESCE(batch_no,'')} 聚合口径一致。同一 key 多行（如同一批号的不同库存状态）数量相加，
 * 与 WMS 侧 SUM 口径一致。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
@Slf4j
@Service
public class WmsKingdeeInventoryReaderImpl implements IWmsKingdeeInventoryReader {

    static final String FORM_ID = "STK_Inventory";

    /** 字段顺序与 {@link #mergeRow} 的下标解析一一对应，改动时同步。 */
    static final String FIELD_KEYS = "FMaterialId.FNumber,FStockId.FNumber,FLot.FNumber,FBaseQty";

    /** 只查 101 组织（江西兆鑫 100 的库存不在永鑫 WMS 范围内）。 */
    static final String ORG_FILTER = "FStockOrgId.FNumber='101'";

    static final int PAGE_SIZE = 500;

    /** key 分隔符。 */
    static final String KEY_SEPARATOR = "|";

    private final WmsKingdeeFormClient formClient;

    public WmsKingdeeInventoryReaderImpl(WmsKingdeeFormClient formClient) {
        this.formClient = formClient;
    }

    @Override
    public Map<String, BigDecimal> queryInventory(List<String> warehouseCodes) {
        String filter = buildFilter(warehouseCodes);
        Map<String, BigDecimal> inventory = new LinkedHashMap<>();
        int startRow = 0;
        while (true) {
            WmsKingdeeResult page = formClient.executeBillQuery(
                    FORM_ID, FIELD_KEYS, filter, PAGE_SIZE, startRow);
            if (!page.isSuccess()) {
                throw new RuntimeException(page.getMessage());
            }
            List<List<Object>> rows = page.getRows() == null ? List.of() : page.getRows();
            for (List<Object> row : rows) {
                mergeRow(inventory, row);
            }
            if (rows.size() < PAGE_SIZE) {
                return inventory;
            }
            startRow += rows.size();
        }
    }

    /** 组织过滤 + 可选仓库过滤；空列表视为不过滤仓库。 */
    private String buildFilter(List<String> warehouseCodes) {
        if (warehouseCodes == null || warehouseCodes.isEmpty()) {
            return ORG_FILTER;
        }
        List<String> codes = new ArrayList<>();
        for (String code : warehouseCodes) {
            if (code != null && !code.trim().isEmpty()) {
                codes.add(code.trim().replace("'", "''"));
            }
        }
        if (codes.isEmpty()) {
            return ORG_FILTER;
        }
        StringBuilder filter = new StringBuilder(ORG_FILTER).append(" AND FStockId.FNumber IN (");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) {
                filter.append(',');
            }
            filter.append('\'').append(codes.get(i)).append('\'');
        }
        return filter.append(')').toString();
    }

    private void mergeRow(Map<String, BigDecimal> inventory, List<Object> row) {
        if (row == null || row.size() < 4) {
            return;
        }
        String material = text(row.get(0));
        String warehouse = text(row.get(1));
        if (material.isEmpty() || warehouse.isEmpty()) {
            // 缺物料/仓库的行无法与 WMS 侧对齐，跳过（不参与比对）
            log.debug("金蝶即时库存存在缺少物料或仓库编码的行，已跳过：{}", row);
            return;
        }
        String key = warehouse + KEY_SEPARATOR + material + KEY_SEPARATOR + text(row.get(2));
        BigDecimal qty = parseQty(row.get(3));
        inventory.merge(key, qty, BigDecimal::add);
    }

    private BigDecimal parseQty(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /** 文本归一：null → ""，并去掉首尾空白（批号的 null/空串因此落到同一 key）。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
