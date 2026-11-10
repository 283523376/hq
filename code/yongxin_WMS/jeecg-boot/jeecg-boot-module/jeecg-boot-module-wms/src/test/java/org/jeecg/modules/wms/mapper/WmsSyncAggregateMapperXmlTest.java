package org.jeecg.modules.wms.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: SB-001 库存/流水聚合 SQL 的口径守卫。
 *
 * <p>这 5 条语句只被对账服务消费、没有独立的单测数据库可跑，
 * SQL 口径（ERP 仓库键 / ERP 物料键 / 仓库从分配行回溯 / 废品仓 LEFT JOIN / 盘点取 adjust_time）
 * 一旦被改坏，对账结果会静默失真。这里从 classpath 直接读 mapper XML 做子串断言，
 * 与 {@link WmsProductionInboundMapperXmlTest} 同一套路。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-03
 */
class WmsSyncAggregateMapperXmlTest {

    /** 从 classpath 读 mapper XML：不依赖测试的工作目录（否则换个目录启动就红）。 */
    private String mapperXml(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/org/jeecg/modules/wms/mapper/xml/" + name)) {
            assertTrue(in != null, "找不到 Mapper XML：" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 截取一条 select 语句的原文（从 id 属性到闭合标签，不含闭合标签），语句不存在时返回空串。 */
    private String selectStatement(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return "";
        }
        return xml.substring(start, xml.indexOf("</select>", start));
    }

    @Test
    void reconcileAggregate_usesErpWarehouseAndMaterialKeys() throws Exception {
        String xml = mapperXml("WmsInventoryMapper.xml");
        String statement = selectStatement(xml, "selectReconcileAggregate");
        assertTrue(!statement.isEmpty(), "selectReconcileAggregate 语句应存在");
        assertTrue(statement.contains("erp_stock_code"),
                "仓库匹配键必须取 wms_warehouse.erp_stock_code（spec §2.3 决策：COALESCE(erp_stock_code, warehouse_code)）");
        assertTrue(statement.contains("kingdee_code"),
                "物料匹配键必须取 wms_material.kingdee_code（B2′ 解绑后引入）");
        assertTrue(statement.contains("GROUP BY"),
                "库存聚合必须按 仓库+物料+批次 分组（对账粒度到仓库，SB-001 拍板）");
    }

    @Test
    void flowStatements_allFourExist() throws Exception {
        String xml = mapperXml("WmsSyncFlowMapper.xml");
        for (String id : new String[]{"selectProductionInboundRows", "selectOutboundRows",
                "selectTransferRows", "selectStocktakeRows"}) {
            assertTrue(!selectStatement(xml, id).isEmpty(), id + " 语句应存在");
        }
    }

    @Test
    void outboundRows_traceWarehouseFromAllocation() throws Exception {
        String xml = mapperXml("WmsSyncFlowMapper.xml");
        String statement = selectStatement(xml, "selectOutboundRows");
        assertTrue(statement.contains("wms_outbound_allocation"),
                "出库单头没有仓库字段，仓库只能从分配行的 location_id 回溯（brief 口径）");
    }

    @Test
    void productionInboundRows_splitsGoodAndDefectWarehouse() throws Exception {
        String xml = mapperXml("WmsSyncFlowMapper.xml");
        String statement = selectStatement(xml, "selectProductionInboundRows");
        assertTrue(statement.contains("scrap_location_id"),
                "不良品行的仓库必须走 scrap_location_id（废品仓），与良品行拆两段 UNION ALL");
        assertTrue(statement.contains("UNION ALL"),
                "良品/不良品两段必须 UNION ALL，各取各的数量");
    }

    @Test
    void stocktakeRows_usesAdjustTimeAsBizTime() throws Exception {
        String xml = mapperXml("WmsSyncFlowMapper.xml");
        String statement = selectStatement(xml, "selectStocktakeRows");
        assertTrue(statement.contains("adjust_time"),
                "盘点的业务时间取 wms_stocktake_order.adjust_time，不是 update_time（spec §2.3）");
        assertTrue(statement.contains("ABS"),
                "盘亏行数量取绝对值，方向由 direction 列表达");
    }
}
