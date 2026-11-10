package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
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
 * @Description: PRD_PickMtrl 回写单测。
 *
 * <p>重点覆盖 2026-10-27 真机校准后的两件事：
 * <ol>
 *   <li><b>必填字段</b>：父项物料 / PPBOM 关联 / 车间 / 数量组五连 / 分录内序列号子单据体</li>
 *   <li><b>前置校验</b>：生产订单已审核、用料清单已审核、批号可用、库存充足——
 *       不满足时要在**调用金蝶 Save 之前**给出可执行原因</li>
 * </ol>
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsKingdeePickWriterImplTest {

    /** PPBOM View 返回的原始报文：一个子项分录，内码 555 */
    private static final String PPBOM_VIEW_JSON =
            "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Result\":{\"BillNo\":\"PPBOM001\","
                    + "\"PPBomEntry\":[{\"Id\":555,\"MaterialID\":{\"Number\":\"WA.1\"}}]}}}";

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsKingdeeLookupSupport lookup;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsKingdeeBatchSupport batchSupport;
    private WmsMaterialCodeResolver materialResolver;
    private WmsKingdeePickWriterImpl writer;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setPickBillType("SCLLD01_SYS");
        properties.setOwnerType("BD_OwnerOrg");
        properties.setKeeperType("BD_KeeperOrg");
        properties.setStockStatusId("KCZT01_SYS");
        properties.setMaterialMap(Map.of("PBC001", "WA.1"));
        lookup = mock(WmsKingdeeLookupSupport.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.resolveKingdeeCode(anyString())).thenAnswer(
                inv -> properties.getMaterialMap().getOrDefault((String) inv.getArgument(0), (String) inv.getArgument(0)));
        when(materialResolver.reverseKingdeeCode(anyString())).thenAnswer(inv -> {
            for (Map.Entry<String, String> e : properties.getMaterialMap().entrySet()) {
                if (e.getValue().equals(inv.getArgument(0))) {
                    return e.getKey();
                }
            }
            return null;
        });
        batchSupport = mock(WmsKingdeeBatchSupport.class);
        when(batchSupport.ensureUsable(anyString(), anyString(), anyString())).thenReturn(null);
        writer = new WmsKingdeePickWriterImpl(formClient, properties, lookup, outboundMapper, materialResolver, batchSupport);

        when(lookup.material("WA.1", "100")).thenReturn(List.of(123L, "WA.1", "子项", 456L, 1L, "100"));
        when(lookup.unitNumber(456L)).thenReturn("zhi");
        when(lookup.stockCode("100", "stereo")).thenReturn("02.002");
        when(lookup.stockLocId("02.002")).thenReturn(null);

        stubHappyPath();
    }

    private void stubHappyPath() {
        // 幂等查询：不存在
        when(formClient.executeBillQuery(eq("PRD_PickMtrl"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
        // 生产订单：已审核、组织 100、车间 201011、父项物料 WA.PARENT
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100008, 100007, 1, "WA.PARENT", "100", "201011", "C")),
                        null, null));
        // 用料清单：已审核
        when(formClient.executeBillQuery(eq("PRD_PPBOM"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("PPBOM001", "C")), null, null));
        when(formClient.view("PRD_PPBOM", "PPBOM001"))
                .thenReturn(WmsKingdeeResult.ok(PPBOM_VIEW_JSON, null, null, null));
        // 批号：可用
        when(formClient.executeBillQuery(eq("BD_BatchMainFile"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("B1", "WA.1", "2")), null, null));
        // 库存：充足
        when(formClient.executeBillQuery(eq("STK_Inventory"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(100)), null, null));
        when(formClient.save(eq("PRD_PickMtrl"), any())).thenReturn(
                WmsKingdeeResult.ok("{}", null, "SCLL001", "id1"));
        when(formClient.submit(eq("PRD_PickMtrl"), eq(List.of("SCLL001"))))
                .thenReturn(WmsKingdeeResult.ok("{}", null, null, null));
    }

    private WmsOutboundOrder order() {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("LL1").setWorkOrderNo("MO1");
        order.setId("o1");
        return order;
    }

    private WmsOutboundDetail detail() {
        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("PBC001")
                .setBatchNo("B1").setActualQty(new BigDecimal("10"));
        detail.setId("d1");
        return detail;
    }

    @Test
    @SuppressWarnings("unchecked")
    void write_buildsAllRequiredFields() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(formClient).save(eq("PRD_PickMtrl"), captor.capture());
        Map<String, Object> model = captor.getValue();

        // 单号以金蝶为准：不再传 FBillNo，由金蝶生成
        assertFalse(model.containsKey("FBillNo"));
        assertEquals("100", ((Map<?, ?>) model.get("FPrdOrgId")).get("FNumber"));
        assertEquals("100", ((Map<?, ?>) model.get("FStockOrgId")).get("FNumber"));
        assertEquals("SCLLD01_SYS", ((Map<?, ?>) model.get("FBillType")).get("FNumber"));
        // 车间必录（单头）
        assertEquals("201011", ((Map<?, ?>) model.get("FWorkShopId")).get("FNumber"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) model.get("FEntity");
        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);

        // 父项物料：缺它金蝶只报「分录明细是必填项」
        assertEquals("WA.PARENT", ((Map<?, ?>) entry.get("FParentMaterialId")).get("FNumber"));
        assertEquals("WA.1", ((Map<?, ?>) entry.get("FMaterialId")).get("FNumber"));
        // 分录车间
        assertEquals("201011", ((Map<?, ?>) entry.get("FEntryWorkShopId")).get("FNumber"));
        // 用料清单关联
        assertEquals("PPBOM001", entry.get("FPPBomBillNo"));
        assertEquals(555L, entry.get("FPPBomEntryId"));
        // 生产订单关联
        assertEquals("MO1", entry.get("FMoBillNo"));
        assertEquals(100008L, entry.get("FMoId"));
        assertEquals(100007L, entry.get("FMoEntryId"));
        // 数量组五连必须齐全且一致
        BigDecimal qty = new BigDecimal("10");
        for (String key : List.of("FAppQty", "FActualQty", "FBaseActualQty",
                "FStockActualQty", "FBaseStockActualQty")) {
            assertEquals(0, qty.compareTo((BigDecimal) entry.get(key)), key + " 必须等于实发数量");
        }
        // 序列号子单据体在分录里
        assertTrue(entry.containsKey("FSerialSubEntity"), "FSerialSubEntity 必须在分录内");
        verify(formClient).submit(eq("PRD_PickMtrl"), eq(List.of("SCLL001")));
    }

    @Test
    void write_rejectsUnauditedWorkOrder() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]",
                        List.of(List.of(100008, 100007, 1, "WA.PARENT", "100", "201011", "A")),
                        null, null));

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("未审核"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsUnauditedPpbom() {
        when(formClient.executeBillQuery(eq("PRD_PPBOM"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("PPBOM001", "A")), null, null));

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("用料清单"), result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("未审核"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsMaterialNotInPpbom() {
        when(formClient.view("PRD_PPBOM", "PPBOM001")).thenReturn(WmsKingdeeResult.ok(
                "{\"Result\":{\"Result\":{\"PPBomEntry\":[{\"Id\":9,\"MaterialID\":{\"Number\":\"OTHER\"}}]}}}",
                null, null, null));

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("用料清单"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsUnusableBatch() {
        // 2026-10-28：批号保障下沉到 WmsKingdeeBatchSupport（缺失自动创建、不可用按配置拒绝），
        // 这里验证「保障不通过时不保存」
        when(batchSupport.ensureUsable(anyString(), anyString(), anyString()))
                .thenReturn("批号[B1]状态为 T（非可用），金蝶拒绝领料");

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("批号"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    @Test
    void write_rejectsInsufficientStock() {
        when(formClient.executeBillQuery(eq("STK_Inventory"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of(3)), null, null));

        WmsOrderSyncResult result = writer.write(order(), List.of(detail()));

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("库存"), result.getErrorMessage());
        verify(formClient, never()).save(anyString(), any());
    }

    /**
     * 2026-10-27 真机踩坑：库存表里 FQty（库存单位数量）可能为 0，而 FBaseQty（基本单位数量）
     * 才是真实库存——用 FQty 判库存会把正常领料全部误拦，所以校验必须查 FBaseQty。
     */
    @Test
    void write_checksStockByBaseQty() {
        assertTrue(writer.write(order(), List.of(detail())).isSuccess());

        ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("STK_Inventory"), fields.capture(), anyString(), anyInt());
        assertEquals("FBaseQty", fields.getValue());
    }

    @Test
    void write_whenDisabled_returnsMock() {
        properties.setEnabled(false);
        assertTrue(writer.write(order(), List.of(detail())).isMocked());
        verify(formClient, never()).save(anyString(), any());
    }
}
