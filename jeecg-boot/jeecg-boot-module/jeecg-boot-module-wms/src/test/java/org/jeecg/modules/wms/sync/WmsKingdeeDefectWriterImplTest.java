package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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
 * @Description: 纯不良品 → 金蝶其他入库单（STK_Miscellaneous）回写单测。
 *
 * <p>覆盖：报文结构（真机验证版）、ERP 单号回存与幂等、前置可读报错、开关 mock。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-28
 */
class WmsKingdeeDefectWriterImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsProductionInboundMapper inboundMapper;
    private WmsKingdeeDefectWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setStockStatusId("KCZT01_SYS");
        properties.getMaterialMap().put("PBC001", "WA.1");
        lookup = mock(WmsKingdeeLookupSupport.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        writer = new WmsKingdeeDefectWriterImpl(formClient, properties, lookup, inboundMapper);

        when(lookup.stockCode("101", "scrap")).thenReturn("F.03");
        when(lookup.material("WA.1", "101")).thenReturn(List.of(123L, "WA.1", "子件", 456L, 1L, "101"));
        when(lookup.unitNumber(456L)).thenReturn("zhi");
        // 幂等查询：金蝶侧不存在
        when(formClient.executeBillQuery(eq("STK_Miscellaneous"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        // 生产订单：组织 101、车间 10.10.14.11
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("101", "10.10.14.11")), null, null));
        when(formClient.save(eq("STK_Miscellaneous"), any()))
                .thenReturn(WmsKingdeeResult.ok("{}", null, "QTRK10126100009", "id1"));
        when(formClient.submit(eq("STK_Miscellaneous"), eq(List.of("QTRK10126100009"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsProductionInbound inbound() {
        WmsProductionInbound inbound = new WmsProductionInbound()
                .setInboundNo("SC1").setWorkOrderNo("MO1").setMaterialCode("PBC001")
                .setBatchNo("B1").setGoodQty(BigDecimal.ZERO).setBadQty(new BigDecimal("4"));
        inbound.setId("i1");
        return inbound;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_buildsVerifiedModel_withoutFBillNo() {
        assertTrue(writer.write(inbound()).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("STK_Miscellaneous"), captor.capture());
        Map<String, Object> model = captor.getValue();

        // 单号以金蝶为准：不得传 FBillNo
        assertFalse(model.containsKey("FBillNo"));
        assertEquals("QTRKD01_SYS", ((Map<?, ?>) model.get("FBillTypeID")).get("FNumber"));
        assertEquals("101", ((Map<?, ?>) model.get("FStockOrgId")).get("FNumber"));
        // 部门缺省取工单车间
        assertEquals("10.10.14.11", ((Map<?, ?>) model.get("FDEPTID")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        assertEquals("WA.1", ((Map<?, ?>) entry.get("FMATERIALID")).get("FNumber"));
        assertEquals("F.03", ((Map<?, ?>) entry.get("FSTOCKID")).get("FNumber"));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) entry.get("FQty")));
        assertEquals(0, new BigDecimal("4").compareTo((BigDecimal) entry.get("FBASEQTY")));
        assertEquals("1", entry.get("FInStockType"));

        // 成功后回存金蝶单号（updateById 只带 id+erpBillNo，不碰其他列）
        verify(inboundMapper).updateById(any(WmsProductionInbound.class));
        verify(formClient).submit(eq("STK_Miscellaneous"), eq(List.of("QTRK10126100009")));
    }

    @Test
    void write_rejectsZeroBadQty() {
        WmsProductionInbound inbound = inbound();
        inbound.setBadQty(BigDecimal.ZERO);
        assertFalse(writer.write(inbound).isSuccess());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsMissingWorkshop() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("101", "")), null, null));
        WmsOrderSyncResult result = writer.write(inbound());
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("车间"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsMissingStockConfig() {
        when(lookup.stockCode("101", "scrap")).thenReturn(null);
        WmsOrderSyncResult result = writer.write(inbound());
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("stock-code.scrap"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_whenErpBillNoExists_followsUpInsteadOfSaving() {
        WmsProductionInbound inbound = inbound();
        inbound.setErpBillNo("QTRK999");
        when(formClient.executeBillQuery(eq("STK_Miscellaneous"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("QTRK999", "B")), null, null));

        assertTrue(writer.write(inbound).isSuccess());
        // 幂等：不再 Save，也不会重复 Submit（B=审核中已提交）
        verify(formClient, never()).save(anyString(), any());
        verify(formClient, never()).submit(anyString(), any());
    }

    @Test
    void write_whenErpBillNoMissingInKingdee_reportsReadableError() {
        WmsProductionInbound inbound = inbound();
        inbound.setErpBillNo("QTRK999");
        when(formClient.executeBillQuery(eq("STK_Miscellaneous"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));

        WmsOrderSyncResult result = writer.write(inbound);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("QTRK999"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_whenDisabled_returnsMock() {
        properties.setEnabled(false);
        assertTrue(writer.write(inbound()).isMocked());
        verify(formClient, never()).save(anyString(), any());
    }
}
