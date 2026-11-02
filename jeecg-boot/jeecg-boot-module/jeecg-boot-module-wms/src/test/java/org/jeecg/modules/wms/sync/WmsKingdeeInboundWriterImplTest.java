package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库回写 PRD_INSTOCK 单测：报文、幂等、纯不良拒绝。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsKingdeeInboundWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private IWmsKingdeeDefectWriter defectWriter;
    private WmsProductionInboundMapper inboundMapper;
    private WmsKingdeeBatchSupport batchSupport;
    private WmsKingdeeLookupSupport lookup;
    private WmsMaterialCodeResolver materialResolver;
    private WmsKingdeeProperties properties;
    private WmsKingdeeInboundWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setDefaultOrgId("209624");
        properties.setDefaultOrgNumber("101");
        properties.setBillType("SCRKD01_SYS");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setOwnerId("209624");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setKeeperId("209624");
        properties.setStockStatusId("KCZT01_SYS");
        properties.setInStockType("1");
        properties.setStockCode(Map.of("stereo", "02.002", "agile", "02.001", "scrap", "02.005"));
        properties.setMaterialMap(Map.of("PBC001", "WA.2206.2201.02000430.A003"));
        // 既有用例覆盖「旧 Save 路径」（成品仍走它）；下推路径由后面的专门用例覆盖
        properties.setPushInboundEnabled(false);
        defectWriter = mock(IWmsKingdeeDefectWriter.class);
        // 混合单会额外调不良品回写；默认给成功，个别用例再按需覆盖
        when(defectWriter.write(any(WmsProductionInbound.class)))
                .thenReturn(WmsOrderSyncResult.ok());
        inboundMapper = mock(WmsProductionInboundMapper.class);
        materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.resolveKingdeeCode(anyString())).thenAnswer(
                inv -> properties.getMaterialMap().getOrDefault((String) inv.getArgument(0), (String) inv.getArgument(0)));
        batchSupport = mock(WmsKingdeeBatchSupport.class);
        when(batchSupport.ensureUsable(anyString(), anyString(), anyString())).thenReturn(null);
        lookup = mock(WmsKingdeeLookupSupport.class);
        writer = new WmsKingdeeInboundWriterImpl(formClient, properties, defectWriter, inboundMapper, materialResolver, batchSupport, lookup);
    }

    private WmsProductionInbound inbound(String good, String bad) {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setInboundNo("SC1");
        record.setWorkOrderNo("WORK00072");
        record.setProductType("半成品");
        record.setMaterialCode("PBC001");
        record.setGoodQty(new BigDecimal(good));
        record.setBadQty(new BigDecimal(bad));
        record.setBatchNo("B1");
        record.setCarrierCode("C1");
        record.setDefectRemark("外观不良");
        record.setCompleteTime(new Date());
        return record;
    }

    private void stubHappyPath() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(575219, "WA.2206.2201.02000430.A003", "半成品", 101588, 1, 209624)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_UNIT"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101588, "zhi", "支")), null, null));
        when(formClient.executeBillQuery(eq("BD_STOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101933, "02.002", 0)), null, null));
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCRK999", "id1"));
        when(formClient.submit(eq("PRD_INSTOCK"), eq(List.of("SCRK999"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_buildsSaveModelAndSubmits() {
        stubHappyPath();

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(result.isSuccess());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("PRD_INSTOCK"), captor.capture());
        Map<String, Object> model = captor.getValue();
        // 单号以金蝶为准：不再传 FBillNo
        assertFalse(model.containsKey("FBillNo"));
        assertEquals("101", ((Map<?, ?>) model.get("FPrdOrgId")).get("FNumber"));
        assertEquals("SCRKD01_SYS", ((Map<?, ?>) model.get("FBillType")).get("FNumber"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.2206.2201.02000430.A003",
                ((Map<?, ?>) entry.get("FMaterialId")).get("FNumber"));
        assertEquals("02.002", ((Map<?, ?>) entry.get("FStockId")).get("FNumber"));
        assertEquals("B1", ((Map<?, ?>) entry.get("FLot")).get("FNumber"));
        assertEquals("zhi", ((Map<?, ?>) entry.get("FUnitID")).get("FNumber"));
        assertEquals("KCZT01_SYS", ((Map<?, ?>) entry.get("FStockStatusId")).get("FNumber"));
        assertEquals("WORK00072", entry.get("FMoBillNo"));
        assertEquals(100006L, entry.get("FMoEntryId"));
        // 主产品行必须显式带「生产订单主产品分录」
        assertEquals(100006L, entry.get("FMOMAINENTRYID"));
        assertEquals(Boolean.FALSE, entry.get("FIsNew"));
        assertEquals("201011", ((Map<?, ?>) entry.get("FWorkShopID")).get("FNumber"));
        assertFalse(((List<?>) entry.get("FEntity_Link")).isEmpty());
        assertEquals(0, ((BigDecimal) entry.get("FRealQty")).compareTo(BigDecimal.ONE));
        assertTrue(String.valueOf(entry.get("FNote")).contains("不良数量:1"));
        verify(formClient).submit("PRD_INSTOCK", List.of("SCRK999"));
    }

    @Test
    void write_existingAudited_isIdempotent() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SC1", "C")), null, null));

        assertTrue(writer.write(inbound("1", "0")).isSuccess());
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).submit(anyString(), any());
    }

    @Test
    void write_existingSubmitted_isIdempotent() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SC1", "B")), null, null));

        assertTrue(writer.write(inbound("1", "0")).isSuccess());
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).submit(anyString(), any());
    }

    @Test
    void write_existingDraft_submits() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SC1", "A")), null, null));
        when(formClient.submit("PRD_INSTOCK", List.of("SC1")))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        assertTrue(writer.write(inbound("1", "0")).isSuccess());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_orgFollowsMo_notDefaultConfig() {
        // 生产订单属于 100 组织（内码 100000）：入库单组织/货主/保管者必须跟随订单，
        // 物料取 FUseOrgId=100000 的记录，仓库用该组织的覆盖配置，而不是默认组织 101
        properties.setStockCodeByOrg(Map.of("100", Map.of("stereo", "99.001")));
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1, 100000, "100")),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(
                                List.of(575219, "WA.2206.2201.02000430.A003", "半成品", 101588, 1, 209624),
                                List.of(999001, "WA.2206.2201.02000430.A003", "半成品", 101589, 1, 100000)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_UNIT"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101589, "zhi100", "支100")), null, null));
        when(formClient.executeBillQuery(eq("BD_STOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101933, "02.002", 0)), null, null));
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCRK998", "id2"));
        when(formClient.submit(eq("PRD_INSTOCK"), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        assertTrue(writer.write(inbound("1", "0")).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("PRD_INSTOCK"), captor.capture());
        Map<String, Object> model = captor.getValue();
        assertEquals("100", ((Map<?, ?>) model.get("FPrdOrgId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) model.get("FStockOrgId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) model.get("FOwnerId0")).get("FNumber"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        Map<String, Object> entry = entries.get(0);
        assertEquals("100", ((Map<?, ?>) entry.get("FOwnerId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) entry.get("FKeeperId")).get("FNumber"));
        // 命中 100 组织的物料记录（基本单位 101589 → zhi100），证明不是拿的 101 记录
        assertEquals("zhi100", ((Map<?, ?>) entry.get("FUnitID")).get("FNumber"));
        // 仓库用 100 组织的覆盖配置，而不是通用 stock-code
        assertEquals("99.001", ((Map<?, ?>) entry.get("FStockId")).get("FNumber"));
    }

    @Test
    void write_pureBadQty_delegatesToDefectWriter() {
        // 2026-10-28：纯不良品不再「请人工处理」，自动开金蝶其他入库单（委托 defect writer）
        WmsProductionInbound record = inbound("0", "2");
        writer.write(record);
        verify(defectWriter).write(record);
    }

    @Test
    void write_pureBadQty_withoutDefectWriter_reportsReadableError() {
        writer = new WmsKingdeeInboundWriterImpl(formClient, properties, null, inboundMapper, materialResolver, batchSupport, lookup);
        WmsOrderSyncResult result = writer.write(inbound("0", "2"));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("纯不良品回写器未注入"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void write_missingMaterial_fails() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        WmsOrderSyncResult result = writer.write(inbound("1", "0"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未找到物料编码"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_locationWarehouse_resolvesStockLocId() {
        // 仓库启用仓位管理（FFlexId>0）：按 stock-loc 配置的仓位编号解析分录内码传 FStockLocId
        properties.setStockLoc(Map.of("02.002", "01"));
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(575219, "WA.2206.2201.02000430.A003", "半成品", 101588, 1, 209624)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_UNIT"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101588, "zhi", "支")), null, null));
        when(formClient.executeBillQuery(eq("BD_STOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101933, "02.002", 100001)), null, null));
        // 值集编号查询 + View 分录
        when(formClient.executeBillQuery(eq("BD_FLEXVALUES"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(100001, "001")), null, null));
        when(formClient.view(eq("BD_FLEXVALUES"), eq("001")))
                .thenReturn(WmsKingdeeResult.ok(
                        "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},"
                                + "\"Result\":{\"FLEXVALUESENTRY\":["
                                + "{\"Id\":100001,\"Number\":\"01\"},{\"Id\":100002,\"Number\":\"02\"}]}}}",
                        null, null, null));
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCRK997", "id3"));
        when(formClient.submit(eq("PRD_INSTOCK"), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        assertTrue(writer.write(inbound("1", "0")).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("PRD_INSTOCK"), captor.capture());
        List<Map<String, Object>> entries = (List<Map<String, Object>>) captor.getValue().get("FEntity");
        // 仓位传分录内码（数值），仓库 02.002 → 值集 001 → 编号 01 → 内码 100001
        assertEquals(100001L, entries.get(0).get("FStockLocId"));
    }

    @Test
    void write_locationWarehouseWithoutConfig_fails() {
        // 启用仓位但未配置 stock-loc：明确报错而不是静默丢字段
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_MATERIAL"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(575219, "WA.2206.2201.02000430.A003", "半成品", 101588, 1, 209624)),
                        null, null));
        when(formClient.executeBillQuery(eq("BD_UNIT"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101588, "zhi", "支")), null, null));
        when(formClient.executeBillQuery(eq("BD_STOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(101933, "02.002", 100001)), null, null));

        WmsOrderSyncResult result = writer.write(inbound("1", "0"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("启用仓位管理"));
        verify(formClient, never()).save(anyString(), any());
    }

    /** 保存失败要原样带出金蝶原文，便于用户按原因处理（不再有 BOM 专用标记）。 */
    @Test
    void write_saveFailure_surfacesKingdeeMessage() {
        stubHappyPath();
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.fail("必录字段为空"));

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(!result.isSuccess());
        assertTrue(result.getErrorMessage().contains("必录字段为空"), "实际：" + result.getErrorMessage());
    }

    // ------------------------------------------------ 半成品：走「生产订单下推」

    private void enablePush(String... flows) {
        properties.setPushInboundEnabled(true);
        java.util.List<WmsKingdeeProperties.FlowBillType> list = new java.util.ArrayList<>();
        for (String f : flows) {
            WmsKingdeeProperties.FlowBillType mapping = new WmsKingdeeProperties.FlowBillType();
            mapping.setFlow(f);
            mapping.setBillType("SCRKD01_SYS");
            list.add(mapping);
        }
        properties.setPushFlowBillTypes(list);
        properties.setDefaultBusinessFlow("生产直接入库流程");
    }

    /** 生产订单行补业务流程列（在既有 8 列之后追加第 11 列）。 */
    private void stubMoWithFlow(String flowName) {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100007, "WORK00072", 101487, "201011", 2000, 1, 100006, 1,
                                209624, "101", flowName)),
                        null, null));
    }

    /** 流程在映射表 → push → 查目标单 → 以 FID 保存（不删分录）→ 提交。 */
    @Test
    void write_semiFinished_pushThenSaveThenSubmit() {
        enablePush("生产直接入库流程");
        stubHappyPath();
        stubMoWithFlow("生产直接入库流程");
        when(formClient.push(eq("PRD_MO"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, null, "657213"));
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), eq("FID=657213"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SCRK999", 1050163)), null, null));
        when(formClient.save(eq("PRD_INSTOCK"), any(), eq(false))).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCRK999", "657213"));
        when(formClient.submit(eq("PRD_INSTOCK"), eq(List.of("SCRK999"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        verify(formClient).push(eq("PRD_MO"), any());
        verify(formClient).save(eq("PRD_INSTOCK"), any(), eq(false));
        verify(formClient).submit(eq("PRD_INSTOCK"), eq(List.of("SCRK999")));
    }

    /** 业务流程不在映射表 → 直接报金蝶原文，不发起下推。 */
    @Test
    void write_unknownBusinessFlow_rejectedWithKingdeeWording() {
        enablePush("生产直接入库流程");
        stubHappyPath();
        stubMoWithFlow("生产汇报入库流程");

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(!result.isSuccess());
        assertTrue(result.getErrorMessage().contains("业务流程"), "实际：" + result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("生产直接入库流程"));
        verify(formClient, never()).push(anyString(), any());
    }

    /** 下推失败 → 原样透传，不继续保存/提交。 */
    @Test
    void write_pushFailure_stopsBeforeSave() {
        enablePush("生产直接入库流程");
        stubHappyPath();
        stubMoWithFlow("生产直接入库流程");
        when(formClient.push(eq("PRD_MO"), any())).thenReturn(
                WmsKingdeeResult.fail("生产订单下推生产入库单的条件：…"));

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(!result.isSuccess());
        assertTrue(result.getErrorMessage().contains("下推"));
        verify(formClient, never()).save(anyString(), any(), eq(false));
        verify(formClient, never()).submit(anyString(), any());
    }

    // ---------- 方案 B：工序汇报来源不回写开单，按 FSrcBillNo 对账 ----------

    private WmsProductionInbound reportSourced() {
        WmsProductionInbound record = inbound("10", "0");
        record.setId("rid1");
        record.setErpEntryKey("GXHB659871#0#10");
        record.setErpBillNo("GXHB659871");
        return record;
    }

    @Test
    void write_reportSourced_neverPushesInstock() {
        // 工序汇报来源：ERP 侧由车间汇报入库生成 PRD_INSTOCK，
        // WMS 再开一张就是双份入库，必须完全不碰 save/push。
        // 这里把「旧直开路径」的桩全部铺好：若实现没有汇报来源分支，就会真的去 save。
        stubHappyPath();
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), contains("FSrcBillNo='GXHB659871'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SCRK101261000099", "B")), null, null));

        WmsOrderSyncResult result = writer.write(reportSourced());

        // 对账命中即视为成功，且全程不得开单
        assertTrue(result.isSuccess(), "对账应命中 ERP 已汇报的入库单");
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).push(anyString(), any());
    }

    @Test
    void reconcile_foundBackfillsScrkNo() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), contains("FSrcBillNo='GXHB659871'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SCRK101261000099", "B")), null, null));

        WmsOrderSyncResult result = writer.write(reportSourced());

        assertTrue(result.isSuccess());
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).updateById(captor.capture());
        assertEquals("SCRK101261000099", captor.getValue().getErpBillNo(),
                "应回填 ERP 真实生产入库单号，替换掉 GXHB 单号");
    }

    @Test
    void reconcile_notFound_keepsGxhbNoAndNoAlert() {
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), contains("FSrcBillNo='GXHB659871'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        WmsOrderSyncResult result = writer.write(reportSourced());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("ERP 尚未汇报入库"), "实际：" + result.getErrorMessage());
        verify(inboundMapper, never()).updateById(any(WmsProductionInbound.class));
    }

    @Test
    void reconcile_prefersBillMatchingQuantity_whenMultipleInboundBills() {
        // 一张工序汇报可能按分录/批次生成多张入库单：WMS 这行是 10 件，
        // 不应盲目取第一张（5 件）——数量对不上的回填会让追溯全部错位
        WmsProductionInbound record = reportSourced();
        record.setGoodQty(new BigDecimal("10"));
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), contains("FSrcBillNo='GXHB659871'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(
                        List.of("SCRK-A", "B", 5.0),
                        List.of("SCRK-B", "C", 10.0)), null, null));

        assertTrue(writer.write(record).isSuccess());

        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).updateById(captor.capture());
        assertEquals("SCRK-B", captor.getValue().getErpBillNo(),
                "应回填数量与本单据一致的那张入库单");
    }

    @Test
    void reconcile_usesEntryKeyAfterBillNoAlreadyBackfilled() {
        // erp_bill_no 已被回填成 SCRK 单号：若还拿它去查 FSrcBillNo，会永久未命中且文案误导
        WmsProductionInbound record = reportSourced();
        record.setErpBillNo("SCRK101261000001");
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(),
                contains("FSrcBillNo='GXHB659871'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("SCRK101261000099", "B", 10.0)), null, null));

        assertTrue(writer.write(record).isSuccess());

        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).updateById(captor.capture());
        assertEquals("SCRK101261000099", captor.getValue().getErpBillNo());
    }

    @Test
    void write_reportSourcedWithZeroGoodQty_neverOpensDefectBill() {
        // 纯不良分支排在方案 B 分支之前：数量被改成 0 时会去开「其他入库单」，正是双份入库
        WmsProductionInbound record = reportSourced();
        record.setGoodQty(BigDecimal.ZERO);
        record.setBadQty(new BigDecimal("10"));
        when(formClient.executeBillQuery(eq("PRD_INSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        writer.write(record);

        verify(defectWriter, never()).write(any(WmsProductionInbound.class));
    }

    /**
     * 混合单（良品 + 不良品）：良品走 PRD_INSTOCK，不良品必须另开其他入库单。
     *
     * <p>过去只回写良品，不良品在金蝶账上漏记——WMS 看得到、ERP 看不到。
     */
    @Test
    void write_mixedOrder_alsoWritesDefectBill() {
        stubHappyPath();
        when(defectWriter.write(any(WmsProductionInbound.class))).thenReturn(WmsOrderSyncResult.ok());

        WmsOrderSyncResult result = writer.write(inbound("10", "2"));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        verify(defectWriter).write(any(WmsProductionInbound.class));
    }

    /** 不良品回写失败：不能报「整单失败」让人以为要重做，文案要说清良品已入库。 */
    @Test
    void write_mixedOrder_defectFails_reportsPartialSuccess() {
        stubHappyPath();
        when(defectWriter.write(any(WmsProductionInbound.class)))
                .thenReturn(WmsOrderSyncResult.fail("金蝶其他入库单保存失败：仓库必填"));

        WmsOrderSyncResult result = writer.write(inbound("10", "2"));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("良品已入库"), result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("不良品回写失败"), result.getErrorMessage());
    }

    /** 良品回写失败时不能再开不良品单：否则会留下一张无对应的孤立单据。 */
    @Test
    void write_goodFails_doesNotWriteDefect() {
        // 不 stub 任何 ERP 响应 → 良品路径失败
        WmsOrderSyncResult result = writer.write(inbound("10", "2"));

        assertFalse(result.isSuccess());
        verify(defectWriter, never()).write(any(WmsProductionInbound.class));
    }

    /** 全良品（不良=0）不应触发不良品回写。 */
    @Test
    void write_allGood_noDefectWrite() {
        stubHappyPath();

        WmsOrderSyncResult result = writer.write(inbound("10", "0"));

        assertTrue(result.isSuccess(), result.getErrorMessage());
        verify(defectWriter, never()).write(any(WmsProductionInbound.class));
    }
}
