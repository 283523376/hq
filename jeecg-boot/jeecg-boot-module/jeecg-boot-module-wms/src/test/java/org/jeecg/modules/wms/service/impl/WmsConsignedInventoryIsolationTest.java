package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.dto.WmsStocktakeCreateDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsStocktakeOrder;
import org.jeecg.modules.wms.entity.WmsTransferDetail;
import org.jeecg.modules.wms.entity.WmsTransferOrder;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeDetailMapper;
import org.jeecg.modules.wms.mapper.WmsStocktakeOrderMapper;
import org.jeecg.modules.wms.mapper.WmsTransferTaskMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.vo.WmsInventoryAvailableVO;
import org.jeecg.modules.wms.vo.WmsInventoryLedgerVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 受托库存与自有库存隔离（蓝图 V2.4，spec 2026-11-04 §3.1）。
 *
 * <p>核心口径：**默认所有库存读路径只取自有（OWNED）**，受托（CONSIGNED）必须显式筛选。
 * 受托材料所有权不属于企业，一旦被自有生产领料/销售出库/调拨/预警/盘点/对账选中，
 * 轻则账实不符，重则把客户的材料发出去。
 *
 * <p>SQL 断言从 classpath 读 mapper XML 做子串校验（与 {@code WmsInventoryMapperXmlTest}
 * 同一套路）：这些语句没有可跑的测试库，口径被改坏时只能靠文本守卫兜底。
 * 另外用 mock 覆盖服务层：即使 mapper 违背 SQL 约定返回了受托行，分配器也必须跳过。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsConsignedInventoryIsolationTest {

    private static final String OWNED_CLAUSE = "ownership_type = 'OWNED'";

    /** 取 LambdaQueryWrapper 的 SQL 片段前必须先注册实体元数据（纯单测没有 MyBatis 启动过程）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WmsInventory.class);
    }

    // ================================================================ XML 口径

    private String mapperXml(String name) throws Exception {
        String path = "/org/jeecg/modules/wms/mapper/xml/" + name;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertTrue(in != null, "找不到 Mapper XML：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 截取一条 select 语句（从 id 属性到闭合标签）。 */
    private String select(String xml, String id) {
        int start = xml.indexOf("<select id=\"" + id + "\"");
        assertTrue(start >= 0, "语句不存在：" + id);
        return xml.substring(start, xml.indexOf("</select>", start));
    }

    /** 截取一条 sql 片段。 */
    private String fragment(String xml, String id) {
        int start = xml.indexOf("<sql id=\"" + id + "\"");
        assertTrue(start >= 0, "SQL 片段不存在：" + id);
        return xml.substring(start, xml.indexOf("</sql>", start));
    }

    /**
     * 真正渲染一条 mapper 语句的 SQL（MyBatis 动态 SQL 会在解析后求值 OGNL）。
     *
     * <p>比纯文本断言强一档：能证明 {@code <choose>}/{@code <if>} 在默认参数下确实落到 OWNED 分支，
     * 而不是「写了条件但分支求值取错」。
     */
    private BoundSql boundSql(String xmlName, String statementId, Object params) throws Exception {
        String resource = "org/jeecg/modules/wms/mapper/xml/" + xmlName;
        MybatisConfiguration configuration = new MybatisConfiguration();
        try (InputStream in = getClass().getResourceAsStream("/" + resource)) {
            assertTrue(in != null, "找不到 Mapper XML：" + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = "org.jeecg.modules.wms.mapper." + xmlName.replace(".xml", "");
        MappedStatement ms = configuration.getMappedStatement(namespace + "." + statementId);
        return ms.getBoundSql(params);
    }

    private String renderSql(String xmlName, String statementId, java.util.Map<String, Object> params)
            throws Exception {
        return boundSql(xmlName, statementId, params).getSql();
    }

    private static java.util.Map<String, Object> params(Object... kv) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void renderedSql_availableAndLedgerDefaultToOwned() throws Exception {
        String available = renderSql("WmsInventoryMapper.xml", "selectAvailable",
                params("materialId", "m1", "batchNo", null, "warehouseType", "立库",
                        "requireContainer", false, "limit", 100));
        assertTrue(available.contains("i.ownership_type = 'OWNED'"), "实际 SQL：" + available);

        String byWarehouse = renderSql("WmsInventoryMapper.xml", "selectAvailableByWarehouse",
                params("materialId", "m1", "batchNo", null, "warehouseId", "w1",
                        "containerCode", null, "requireContainer", false, "limit", 100));
        assertTrue(byWarehouse.contains("i.ownership_type = 'OWNED'"), "实际 SQL：" + byWarehouse);

        String ledger = renderSql("WmsInventoryMapper.xml", "selectLedgerPage",
                params("q", new WmsInventoryLedgerQueryDTO()));
        assertTrue(ledger.contains("i.ownership_type = 'OWNED'"), "实际 SQL：" + ledger);

        // 动态 SQL 的默认分支必须真的求值到 OWNED：<where> 把首个 AND 吃掉后仍要留下条件
        String reconcile = renderSql("WmsInventoryMapper.xml", "selectReconcileAggregate",
                params("windowStart", null, "warehouseCodes", null, "ownershipType", null));
        assertTrue(reconcile.contains("i.ownership_type = 'OWNED'"), "实际 SQL：" + reconcile);
    }

    @Test
    void renderedSql_explicitConsignedFiltersByConsignor() throws Exception {
        WmsInventoryLedgerQueryDTO query = new WmsInventoryLedgerQueryDTO();
        query.setOwnershipType(WmsConstants.OWNERSHIP_CONSIGNED);
        query.setConsignorId("SUP-1");

        String sql = renderSql("WmsInventoryMapper.xml", "selectLedgerPage", params("q", query));
        assertTrue(sql.contains("i.ownership_type = 'CONSIGNED'"), "实际 SQL：" + sql);
        assertTrue(sql.contains("i.consignor_id = ?"), "受托筛选必须能按受托方过滤，实际 SQL：" + sql);
        assertFalse(sql.contains("i.ownership_type = 'OWNED'"),
                "显式查受托时不能再叠一层 OWNED 条件，实际 SQL：" + sql);
    }

    @Test
    void availableQuantitySql_onlyOwned() throws Exception {
        String xml = mapperXml("WmsInventoryMapper.xml");
        assertTrue(select(xml, "selectAvailable").contains(OWNED_CLAUSE),
                "出库可用量必须只取自有库存，否则受托材料会被自有生产领料/销售出库占用");
        assertTrue(select(xml, "selectAvailableByWarehouse").contains(OWNED_CLAUSE),
                "调拨可用量必须只取自有库存");
        // 服务层靠这一列二次兜底，SQL 不查它就兜不住
        assertTrue(select(xml, "selectAvailable").contains("i.ownership_type,"),
                "可用量行必须带出 ownership_type，分配器才能在服务层再做一次过滤");
        assertTrue(select(xml, "selectAvailableByWarehouse").contains("i.ownership_type,"),
                "可用量行必须带出 ownership_type");
    }

    @Test
    void ledgerSql_defaultsOwned_andConsignedFiltersByConsignor() throws Exception {
        String where = fragment(mapperXml("WmsInventoryMapper.xml"), "ledgerWhere");
        assertTrue(where.contains("ownership_type = 'OWNED'"),
                "台账/导出默认分支必须是正向匹配 OWNED");
        assertTrue(where.contains("'CONSIGNED'"),
                "台账必须支持显式筛选受托库存（<otherwise> 之外的 CONSIGNED 分支）");
        assertTrue(where.contains("consignor_id"),
                "显式筛选受托时必须能按受托方过滤（consignor_id）");
        assertTrue(where.contains("q.consignorId"),
                "受托方过滤条件应来自查询 DTO 的 consignorId");
    }

    @Test
    void exportCursor_coversFullSixColumnKey() throws Exception {
        String cursor = select(mapperXml("WmsInventoryMapper.xml"), "selectLedgerCursor");
        assertTrue(cursor.contains("i.ownership_type, i.consignor_id"),
                "导出游标比较/排序必须带所有权两列：六列唯一键下同一四元组可能有两行，"
                        + "只按前四列比较会静默跳过行");
        assertTrue(cursor.contains("#{lastOwnershipType}") && cursor.contains("#{lastConsignorId}"),
                "游标的参数必须包含所有权两列");
    }

    /** 页面分页与导出游标必须同一排序：并列行在分页边界上没有稳定顺序，会重复/丢行。 */
    @Test
    void ledgerPage_ordersBySameSixColumnsAsCursor() throws Exception {
        String xml = mapperXml("WmsInventoryMapper.xml");
        String pageOrder = select(xml, "selectLedgerPage").substring(
                select(xml, "selectLedgerPage").indexOf("ORDER BY"));
        String cursorOrder = select(xml, "selectLedgerCursor").substring(
                select(xml, "selectLedgerCursor").indexOf("ORDER BY"));
        for (String column : new String[]{"i.material_id", "i.location_id", "i.batch_no",
                "i.container_code", "i.ownership_type", "i.consignor_id"}) {
            assertTrue(pageOrder.contains(column), "分页排序缺少 " + column + "：" + pageOrder);
            assertTrue(cursorOrder.contains(column), "游标排序缺少 " + column + "：" + cursorOrder);
        }
    }

    /**
     * 上架聚合必须把所有权两列写进 INSERT：唯一键自 V2.4 起含它们，
     * 沿用固定列清单会让受托到货落到 DB 默认值 OWNED、并入同四元组的自有行。
     */
    @Test
    void upsertInventory_carriesOwnershipAndConsignor() throws Exception {
        WmsInventory consigned = new WmsInventory();
        consigned.setOwnershipType(WmsConstants.OWNERSHIP_CONSIGNED);
        consigned.setConsignorId("SUP-1");

        BoundSql bound = boundSql("WmsInventoryMapper.xml", "upsertInventory", consigned);
        String sql = bound.getSql();
        assertTrue(sql.contains("ownership_type") && sql.contains("consignor_id"),
                "upsertInventory 的 INSERT 列必须含所有权两列，实际：" + sql);
        assertTrue(sql.contains("COALESCE(?, 'OWNED')"),
                "所有权列必须带 OWNED 兜底（旧调用方不传时落自有），实际：" + sql);
        assertTrue(sql.contains("COALESCE(?, '')"),
                "受托方列必须带空串兜底（自有库存的 consignor_id 是空串不是 NULL），实际：" + sql);

        List<String> properties = bound.getParameterMappings().stream()
                .map(org.apache.ibatis.mapping.ParameterMapping::getProperty).toList();
        assertTrue(properties.contains("ownershipType"), "所有权必须作为绑定参数：" + properties);
        assertTrue(properties.contains("consignorId"), "受托方必须作为绑定参数：" + properties);

        // 传入受托来源时，参数取实体值；不传时保持 null，由 COALESCE 在 SQL 侧回落 OWNED/''
        assertEquals(WmsConstants.OWNERSHIP_CONSIGNED, valueOf(consigned, "ownershipType"));
        assertEquals("SUP-1", valueOf(consigned, "consignorId"));
        WmsInventory plain = new WmsInventory();
        assertEquals(null, valueOf(plain, "ownershipType"));
        assertEquals(null, valueOf(plain, "consignorId"));
    }

    private Object valueOf(WmsInventory entity, String property) {
        return org.apache.ibatis.reflection.SystemMetaObject.forObject(entity).getValue(property);
    }

    @Test
    void reconcileAndDiffLookupDefaultOwned() throws Exception {
        String xml = mapperXml("WmsInventoryMapper.xml");
        assertTrue(select(xml, "selectReconcileAggregate").contains("ownership_type"),
                "SB-001 对账域只含自有库存，受托不参与金蝶即时库存对账");
        assertTrue(select(xml, "selectLocationIdsForDiff").contains(OWNED_CLAUSE),
                "由差异发起盘点时反查库位必须与对账同口径，否则只堆受托货的库位会被拉进盘点");
    }

    @Test
    void warningScansOnlyOwned() throws Exception {
        String xml = mapperXml("WmsInventoryWarningMapper.xml");
        assertTrue(select(xml, "selectAvailableByMaterial").contains(OWNED_CLAUSE),
                "库存下限预警只应统计自有库存");
        assertTrue(select(xml, "selectOverdueRows").contains(OWNED_CLAUSE),
                "存放超期预警只应扫描自有库存");
    }

    @Test
    void batchTraceStockOnlyOwned() throws Exception {
        assertTrue(select(mapperXml("WmsBatchTraceMapper.xml"), "selectBatchStock")
                        .contains(OWNED_CLAUSE),
                "批次追溯的当前库存默认只显示自有");
    }

    /**
     * 反向匹配守卫：存量行的 ownership_type/source 可能为 NULL，
     * {@code <> 'CONSIGNED'} 会把它们漏掉，表现为「有货却分不出来」。一律正向匹配 OWNED。
     */
    @Test
    void inventorySql_neverUsesNegativeOwnershipMatch() throws Exception {
        for (String name : new String[]{"WmsInventoryMapper.xml", "WmsInventoryWarningMapper.xml",
                "WmsBatchTraceMapper.xml"}) {
            // 注释里会引用反例，先剔掉注释再扫 SQL 正文
            String sql = mapperXml(name).replaceAll("(?s)<!--.*?-->", "");
            // XML 里 `<>` 必须写成 &lt;&gt;，只查字面 `<>` 永远抓不到真实违规
            assertFalse(sql.contains("<> 'CONSIGNED'") || sql.contains("&lt;&gt; 'CONSIGNED'")
                            || sql.contains("!= 'CONSIGNED'"),
                    name + " 不允许用负向匹配排除受托行：NULL 存量行会被一起漏掉");
        }
    }

    // ================================================================ 台账服务

    @Test
    void ledgerQuery_defaultsToOwned() {
        WmsInventoryLedgerQueryDTO n =
                WmsInventoryLedgerServiceImpl.normalize(new WmsInventoryLedgerQueryDTO());
        assertEquals(WmsConstants.OWNERSHIP_OWNED, n.getOwnershipType(),
                "不传库存类型时默认只能看到自有库存");
    }

    @Test
    void ledgerQuery_explicitConsignedKeepsConsignor() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setOwnershipType(WmsConstants.OWNERSHIP_CONSIGNED);
        q.setConsignorId(" SUP-1 ");
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertEquals(WmsConstants.OWNERSHIP_CONSIGNED, n.getOwnershipType());
        assertEquals("SUP-1", n.getConsignorId());
    }

    /** 自有查询不该带受托方条件：带着 consignorId 查 OWNED 会让结果意外为空。 */
    @Test
    void ledgerQuery_ownedDropsConsignor() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setConsignorId("SUP-1");
        WmsInventoryLedgerQueryDTO n = WmsInventoryLedgerServiceImpl.normalize(q);
        assertEquals(WmsConstants.OWNERSHIP_OWNED, n.getOwnershipType());
        assertEquals(null, n.getConsignorId());
    }

    @Test
    void ledgerQuery_rejectsUnknownOwnership() {
        WmsInventoryLedgerQueryDTO q = new WmsInventoryLedgerQueryDTO();
        q.setOwnershipType("BORROWED");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> WmsInventoryLedgerServiceImpl.normalize(q));
        assertTrue(e.getMessage().contains("OWNED"), e.getMessage());
    }

    // ================================================================ 废品台账

    @Test
    void defectLedger_queriesOwnedOnly() {
        WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
        WmsOutboundOrderMapper orderMapper = mock(WmsOutboundOrderMapper.class);
        WmsOutboundDetailMapper detailMapper = mock(WmsOutboundDetailMapper.class);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsWarehouseMapper warehouseMapper = mock(WmsWarehouseMapper.class);
        WmsProductionInboundMapper inboundMapper = mock(WmsProductionInboundMapper.class);
        WmsMaterialMapper materialMapper = mock(WmsMaterialMapper.class);
        IWmsAuditLogService auditLogService = mock(IWmsAuditLogService.class);
        WmsDefectServiceImpl service = new WmsDefectServiceImpl(inventoryMapper, orderMapper,
                detailMapper, locationMapper, warehouseMapper, inboundMapper, materialMapper,
                auditLogService);

        WmsWarehouse scrap = new WmsWarehouse().setWarehouseType(WmsConstants.WAREHOUSE_TYPE_SCRAP);
        scrap.setId("w-scrap");
        when(warehouseMapper.selectList(any())).thenReturn(List.of(scrap));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());

        service.pageLedger(1, 20, null, null, null);

        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor =
                ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(inventoryMapper).selectLedgerPage(any(), captor.capture());
        assertEquals(WmsConstants.OWNERSHIP_OWNED, captor.getValue().getOwnershipType(),
                "不良品台账默认只列自有库存；受托不良按受托方独立记账");
    }

    // ================================================================ 盘点

    @Test
    void stocktakeCreate_scopesToOwned() {
        WmsStocktakeOrderMapper orderMapper = mock(WmsStocktakeOrderMapper.class);
        WmsStocktakeDetailMapper detailMapper = mock(WmsStocktakeDetailMapper.class);
        WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsStocktakeServiceImpl service = new WmsStocktakeServiceImpl(orderMapper, detailMapper,
                inventoryMapper, locationMapper, mock(WmsStocktakeStatusResolver.class));

        when(inventoryMapper.selectList(any())).thenReturn(List.of());

        WmsStocktakeCreateDTO dto = new WmsStocktakeCreateDTO();
        dto.setLocationIds(List.of("loc-1"));
        assertThrows(RuntimeException.class, () -> service.create(dto, "tester"),
                "空库存时盘点单应报错——这也证明桩数据确实被读到了");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<WmsInventory>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(inventoryMapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("ownership_type"),
                "盘点范围必须限定自有库存，实际：" + captor.getValue().getSqlSegment());
    }

    // ================================================================ 分配器（服务层兜底）

    private WmsInventoryAvailableVO row(String id, String qty, String ownership) {
        WmsInventoryAvailableVO vo = new WmsInventoryAvailableVO();
        vo.setId(id);
        vo.setMaterialId("m1");
        vo.setLocationId("loc-" + id);
        vo.setLocationCode("L-" + id);
        vo.setContainerCode("");
        vo.setBatchNo("B1");
        vo.setQuantity(new BigDecimal(qty));
        vo.setLockedQty(BigDecimal.ZERO);
        vo.setOwnershipType(ownership);
        return vo;
    }

    /**
     * 受托行排在最前面（FIFO 更早）也不得被自有出库选中。
     * SQL 已经过滤一次，这条测的是服务层兜底：mapper 被改坏/被 mock 成返回受托行时仍不能超发。
     */
    @Test
    void outboundAllocator_skipsConsignedRows() {
        WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
        WmsOutboundAllocationMapper allocationMapper = mock(WmsOutboundAllocationMapper.class);
        WmsOutboundDetailMapper detailMapper = mock(WmsOutboundDetailMapper.class);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsOutboundAllocatorImpl allocator = new WmsOutboundAllocatorImpl(inventoryMapper,
                allocationMapper, detailMapper, locationMapper);

        when(inventoryMapper.selectAvailable(any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(
                        row("CONSIGNED-1", "100", WmsConstants.OWNERSHIP_CONSIGNED),
                        row("OWNED-1", "10", WmsConstants.OWNERSHIP_OWNED)));
        when(inventoryMapper.lockInventory(eq("OWNED-1"), any())).thenReturn(1);

        WmsOutboundOrder order = new WmsOutboundOrder()
                .setOutboundNo("LL1").setOutboundType(WmsConstants.OUTBOUND_TYPE_PICK);
        order.setId("o1");
        WmsOutboundDetail detail = new WmsOutboundDetail().setOutboundId("o1").setLineNo(1)
                .setMaterialId("m1").setMaterialCode("PBC001")
                .setRequiredQty(new BigDecimal("5")).setAllocatedQty(BigDecimal.ZERO);
        detail.setId("d1");

        allocator.allocate(order, List.of(detail));

        verify(inventoryMapper, never()).lockInventory(eq("CONSIGNED-1"), any());
        verify(inventoryMapper, times(1)).lockInventory("OWNED-1", new BigDecimal("5"));
        ArgumentCaptor<org.jeecg.modules.wms.entity.WmsOutboundAllocation> captor =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsOutboundAllocation.class);
        verify(allocationMapper, times(1)).insert(captor.capture());
        assertEquals("OWNED-1", captor.getValue().getInventoryId(),
                "只允许分配自有库存行");
    }

    @Test
    void transferAllocator_skipsConsignedRows() {
        WmsInventoryMapper inventoryMapper = mock(WmsInventoryMapper.class);
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsWarehouseMapper warehouseMapper = mock(WmsWarehouseMapper.class);
        WmsTransferTaskMapper taskMapper = mock(WmsTransferTaskMapper.class);
        WmsTransferInventoryServiceImpl service = new WmsTransferInventoryServiceImpl(
                inventoryMapper, locationMapper, warehouseMapper, taskMapper);

        WmsWarehouse stereo = new WmsWarehouse()
                .setWarehouseType(WmsConstants.WAREHOUSE_TYPE_STEREO);
        stereo.setId("w1");
        when(warehouseMapper.selectById("w1")).thenReturn(stereo);
        when(inventoryMapper.selectAvailableByWarehouse(any(), any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(List.of(
                        row("CONSIGNED-2", "100", WmsConstants.OWNERSHIP_CONSIGNED),
                        row("OWNED-2", "10", WmsConstants.OWNERSHIP_OWNED)));
        when(inventoryMapper.lockInventory(eq("OWNED-2"), any())).thenReturn(1);

        WmsTransferOrder order = new WmsTransferOrder().setOutWarehouseId("w1");
        order.setId("tr-1");
        WmsTransferDetail detail = new WmsTransferDetail().setMaterialId("m1")
                .setMaterialCode("PBC001").setTransferQty(new BigDecimal("4"));
        detail.setId("td-1");

        service.allocate(order, detail);

        verify(inventoryMapper, never()).lockInventory(eq("CONSIGNED-2"), any());
        verify(inventoryMapper, times(1)).lockInventory("OWNED-2", new BigDecimal("4"));
        ArgumentCaptor<org.jeecg.modules.wms.entity.WmsTransferTask> captor =
                ArgumentCaptor.forClass(org.jeecg.modules.wms.entity.WmsTransferTask.class);
        verify(taskMapper, times(1)).insert(captor.capture());
        assertEquals("OWNED-2", captor.getValue().getInventoryId(),
                "移库任务只能挂在自有库存行上");
    }
}
