package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
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
        writer = new WmsKingdeeInboundWriterImpl(formClient, properties);
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
        assertEquals("SC1", model.get("FBillNo"));
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
    void write_pureBadQty_isRejected() {
        WmsOrderSyncResult result = writer.write(inbound("0", "2"));
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("不支持纯不良品"));
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

    /**
     * 半成品被金蝶以「BOM版本不能为空」拒绝时，必须打 {@code [BOM_PENDING]} 前缀：
     * 前端据此把「同步金蝶」置灰，避免用户对着必然失败的按钮反复重试。
     */
    @Test
    void write_bomRejection_markedAsBlocked() {
        stubHappyPath();
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.fail("单据编号为“X”的生产入库单，第1行分录，物料启用BOM版本管理，BOM版本不能为空"));

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(!result.isSuccess());
        assertTrue(result.getErrorMessage().startsWith(WmsConstants.SYNC_BLOCK_BOM_PENDING),
                "必须以固定前缀标识，实际：" + result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("BOM版本不能为空"), "要保留金蝶原文便于排查");
    }

    /** 其它保存失败不得被打上 BOM 标记，否则用户会被告知错误的原因。 */
    @Test
    void write_otherSaveFailure_notMarkedAsBom() {
        stubHappyPath();
        when(formClient.save(eq("PRD_INSTOCK"), any())).thenReturn(
                WmsKingdeeResult.fail("金蝶生产入库保存失败：必录字段为空"));

        WmsOrderSyncResult result = writer.write(inbound("1", "1"));

        assertTrue(!result.getErrorMessage().startsWith(WmsConstants.SYNC_BLOCK_BOM_PENDING));
    }
}
