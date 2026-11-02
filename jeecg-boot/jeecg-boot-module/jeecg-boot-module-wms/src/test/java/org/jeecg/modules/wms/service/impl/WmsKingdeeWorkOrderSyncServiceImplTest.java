package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsMaterialCodeResolver;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 金蝶 PRD_MO → WMS 工单同步单测（V2.2：审核门槛/时间窗/多批次/同步日志/回源）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsKingdeeWorkOrderSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private IWmsWorkOrderService workOrderService;
    private WmsMaterialMapper materialMapper;
    private IWmsSyncLogService syncLogService;
    private WmsMaterialCodeResolver materialResolver;
    private WmsKingdeeWorkOrderSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setMaterialMap(Map.of("PBC001", "WA.2206.2201.02000430.A003"));
        properties.setMaterialProductType(Map.of("PBC001", "半成品"));
        properties.setProductTypeMap(Map.of("1", "半成品"));
        workOrderService = mock(IWmsWorkOrderService.class);
        materialMapper = mock(WmsMaterialMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.resolveProductType(anyString())).thenAnswer(
                inv -> properties.getMaterialProductType().get((String) inv.getArgument(0)));
        when(materialResolver.reverseKingdeeCode(anyString())).thenAnswer(inv -> {
            for (Map.Entry<String, String> e : properties.getMaterialMap().entrySet()) {
                if (e.getValue().equals(inv.getArgument(0))) {
                    return e.getKey();
                }
            }
            return null;
        });
        service = new WmsKingdeeWorkOrderSyncServiceImpl(
                formClient, properties, workOrderService, materialMapper, syncLogService, materialResolver);
    }


    /** 可选依赖是字段注入，单测里手动塞进去。 */
    private static void injectAudit(Object target, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField("auditLogService");
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 二次同步必须能看出「同步前 WMS 是什么状态」——ERP 作废后靠它解释错误。 */
    @Test
    void audit_recordsBeforeAndAfterOnResync() throws Exception {
        IWmsAuditLogService auditService = mock(IWmsAuditLogService.class);
        when(auditService.snapshot(any(), any(String[].class))).thenReturn("{\"workOrderStatus\":\"x\"}");
        injectAudit(service, auditService);

        WmsWorkOrder existingOrder = new WmsWorkOrder()
                .setWorkOrderNo("WORK00072").setWorkOrderStatus("待生产");
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("2000"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00072")).thenReturn(existingOrder);

        service.syncFromKingdee("WORK00072", null, null, 50);

        ArgumentCaptor<WmsAuditEvent> captor = ArgumentCaptor.forClass(WmsAuditEvent.class);
        verify(auditService).record(captor.capture());
        WmsAuditEvent event = captor.getValue();
        assertEquals("工单", event.getBizType());
        assertEquals("WORK00072", event.getBizNo());
        assertEquals("同步落地", event.getAction());
        assertEquals("ERP同步", event.getChannel());
        assertEquals("system", event.getOperator());
        // before 必须取 WMS 原记录（而不是本次推入的 DTO）
        verify(auditService).snapshot(org.mockito.ArgumentMatchers.same(existingOrder),
                any(String[].class));
        assertNotNull(event.getBeforeValue());
        assertNotNull(event.getAfterValue());
    }

    private WmsKingdeeResult rows(List<List<Object>> rows) {
        return WmsKingdeeResult.ok("[]", rows, null, null);
    }

    private WmsMaterial pbc001() {
        WmsMaterial material = new WmsMaterial();
        material.setMaterialCode("PBC001");
        return material;
    }

    @Test
    void gateCMapsReleased_ABMapsPending_unknownSkipped() {
        assertEquals("已下达", WmsKingdeeWorkOrderSyncServiceImpl.gateStatus("C"));
        assertEquals("待生产", WmsKingdeeWorkOrderSyncServiceImpl.gateStatus("A"));
        assertEquals("待生产", WmsKingdeeWorkOrderSyncServiceImpl.gateStatus("B"));
        assertNull(WmsKingdeeWorkOrderSyncServiceImpl.gateStatus("D"));
        assertNull(WmsKingdeeWorkOrderSyncServiceImpl.gateStatus("Z"));
    }

    /**
     * 模型 B（2026-10-28）：没有产品类型映射也要照常导入——金蝶里没有成品/半成品之分，
     * 产品类型由入库来源决定（工序汇报=半成品、完工入库=成品），工单不该因缺该字段被跳过。
     */
    @Test
    void sync_withoutProductTypeMapping_stillImports() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("MO-NOTYPE", "C", "2",
                        "UNMAPPED.001", "未映射物料", "0", new BigDecimal("100"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("MO-NOTYPE")).thenReturn(null);

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("MO-NOTYPE", null, null, 50);

        assertEquals(1, vo.getImported(), String.join("；", vo.getMessages()));
        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertNull(captor.getValue().getProductType());
    }

    @Test
    void sync_singleMaterial_importsWorkOrder_asReleasedWithErpSource() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("2000"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00072")).thenReturn(null);

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", null, null, 50);

        assertEquals(1, vo.getQueried());
        assertEquals(1, vo.getImported());
        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertEquals("WORK00072", captor.getValue().getWorkOrderNo());
        assertEquals("PBC001", captor.getValue().getMaterialCode());
        assertEquals(0, captor.getValue().getPlanQty().compareTo(new BigDecimal("2000")));
        assertEquals("半成品", captor.getValue().getProductType());
        assertEquals("已下达", captor.getValue().getWorkOrderStatus());
        assertEquals("ERP", captor.getValue().getSource());
        assertEquals("C", captor.getValue().getErpDocumentStatus());
        verify(syncLogService).logSync(eq("ERP_TO_WMS"), eq("WORK_ORDER"), eq("WORK00072"),
                eq("KINGDEE"), eq(true), anyInt(), any(), any(), any(), anyLong());
    }

    @Test
    void sync_pendingOrder_landsAsPending() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00073", "B", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("100"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00073")).thenReturn(null);

        service.syncFromKingdee("WORK00073", null, null, 50);

        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertEquals("待生产", captor.getValue().getWorkOrderStatus());
        assertEquals("B", captor.getValue().getErpDocumentStatus());
    }

    @Test
    void unknownDocStatusSkippedNotImported() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00074", "D", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01"))));

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00074", null, null, 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("D"));
        verify(workOrderService, never()).push(any());
    }

    @Test
    void sync_unknownMaterial_isSkipped() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.xxxx", "未知物料", "0", new BigDecimal("10"), "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(null);

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", null, null, 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("WMS 物料不存在"));
        verify(workOrderService, never()).push(any());
    }

    @Test
    void sync_multipleMaterials_isSkipped() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(
                        List.of("WORK00072", "C", "1", "WA.1", "物料1", "0", new BigDecimal("1"), "2026-10-01"),
                        List.of("WORK00072", "C", "1", "WA.2", "物料2", "0", new BigDecimal("1"), "2026-10-01"))));

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", null, null, 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("多个物料"));
    }

    @Test
    void multiLotOrderSkippedWithMessage() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(
                        List.of("WORK00075", "C", "1", "WA.2206.2201.02000430.A003", "半成品",
                                "LOT1", new BigDecimal("1"), "2026-10-01"),
                        List.of("WORK00075", "C", "1", "WA.2206.2201.02000430.A003", "半成品",
                                "LOT2", new BigDecimal("1"), "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00075", null, null, 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("批次"));
        verify(workOrderService, never()).push(any());
    }

    @Test
    void dateWindowFilterBuiltAndEchoed() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee(null, "2026-10-01", "2026-10-15", 50);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PRD_MO"), anyString(), filter.capture(), anyInt());
        assertTrue(filter.getValue().contains("FDate>='2026-10-01'"));
        assertTrue(filter.getValue().contains("FDate<='2026-10-15'"));
        assertEquals("2026-10-01", vo.getEffectiveStartDate());
        assertEquals("2026-10-15", vo.getEffectiveEndDate());
    }

    @Test
    void noFilterDefaultsToSevenDayWindowAndEchoes() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee(null, null, null, null);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PRD_MO"), anyString(), filter.capture(), eq(50));
        assertTrue(filter.getValue().contains("FDate>="));
        assertEquals(7, vo.getEffectiveWindowDays());
    }

    @Test
    void filterIncludesProductionOrg() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));

        service.syncFromKingdee(null, "2026-10-01", "2026-10-15", 50);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PRD_MO"), anyString(), filter.capture(), anyInt());
        // 不限定生产组织会把其它组织（100 江西兆鑫）的工单拉进来，物料必然匹配不上
        assertTrue(filter.getValue().contains("FPrdOrgId.FNumber='101'"));
    }

    @Test
    void filterOmitsOrgWhenConfiguredBlank() {
        properties.setWorkOrderOrgNumber("");
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));

        service.syncFromKingdee(null, "2026-10-01", "2026-10-15", 50);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("PRD_MO"), anyString(), filter.capture(), anyInt());
        assertTrue(!filter.getValue().contains("FPrdOrgId"));
    }

    @Test
    void sync_queryFailure_throws() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("鉴权失败"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncFromKingdee("WORK00072", null, null, 50));
        assertTrue(e.getMessage().contains("金蝶工单查询失败"));
    }

    @Test
    void sync_existingWorkOrder_countsUpdate() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "LOT1", new BigDecimal("5"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00072")).thenReturn(new WmsWorkOrder());

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", null, null, 50);

        assertEquals(1, vo.getUpdated());
        assertEquals(0, vo.getImported());
    }

    @Test
    void syncSingleFallsBackForScan() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WO1", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WO1")).thenReturn(null);
        WmsWorkOrderInfoVO info = new WmsWorkOrderInfoVO();
        info.setWorkOrderNo("WO1");
        when(workOrderService.scan("WO1"))
                .thenThrow(new RuntimeException("工单号无效"))
                .thenReturn(info);

        WmsWorkOrderInfoVO result = service.scanWithErpFallback("WO1");

        assertEquals("WO1", result.getWorkOrderNo());
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient, times(1)).executeBillQuery(eq("PRD_MO"), anyString(), filter.capture(), anyInt());
        assertTrue(filter.getValue().contains("FBillNo='WO1'"));
    }

    @Test
    void scanWithErpFallback_propagatesWhenSyncFindsNothing() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));
        when(workOrderService.scan("NOPE")).thenThrow(new RuntimeException("工单号无效"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.scanWithErpFallback("NOPE"));
        assertEquals("工单号无效", e.getMessage());
    }

    @Test
    void sync_pushFailureMarksWorkOrderAndLogs() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00076", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00076")).thenReturn(new WmsWorkOrder());
        org.mockito.Mockito.doThrow(new RuntimeException("物料编码不存在"))
                .when(workOrderService).push(any());

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00076", null, null, 50);

        assertEquals(1, vo.getSkipped());
        verify(workOrderService).markSyncFailed(eq("WORK00076"), contains("物料编码不存在"));
        verify(syncLogService).logSync(eq("ERP_TO_WMS"), eq("WORK_ORDER"), eq("WORK00076"),
                eq("KINGDEE"), eq(false), anyInt(), any(), any(), contains("物料编码不存在"), anyLong());
    }

    @Test
    void sync_queryFailureWritesFailureLog() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("鉴权失败"));

        assertThrows(RuntimeException.class, () -> service.syncFromKingdee(null, null, null, 50));

        verify(syncLogService).logSync(eq("ERP_TO_WMS"), eq("WORK_ORDER"), eq(null),
                eq("KINGDEE"), eq(false), anyInt(), any(), any(), contains("鉴权失败"), anyLong());
    }

    @Test
    void invalidDateRejectedBeforeQuery() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncFromKingdee(null, "2026/01/01", null, 50));
        assertEquals("日期格式必须为 yyyy-MM-dd", e.getMessage());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void startAfterEndRejected() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncFromKingdee(null, "2026-10-10", "2026-10-01", 50));
        assertEquals("开始日期不能晚于结束日期", e.getMessage());
    }

    @Test
    void syncSingleUsesMaxTopToFetchAllEntries() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of()));
        service.syncSingleFromKingdee("WO1");
        verify(formClient).executeBillQuery(eq("PRD_MO"), anyString(), anyString(), eq(200));
    }

    // ------------------------------------------------------------ order_type（spec 2026-10-26 §5.3）

    @Test
    void orderTypeStaysNullWhenFieldNotConfigured() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00080", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00080")).thenReturn(null);

        service.syncFromKingdee("WORK00080", null, null, 50);

        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertNull(captor.getValue().getOrderType());
        // 未配置类型字段时，查询字段串不得追加
        verify(formClient).executeBillQuery(eq("PRD_MO"),
                eq("FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty,FDate"),
                anyString(), anyInt());
    }

    @Test
    void orderTypeMappedFromConfiguredKingdeeField() {
        properties.getMasterData().setOrderTypeField("F_UNW_Text_hp8");
        properties.getMasterData().getOrderTypeValueMap().put("客户订单", "客户订单");
        when(formClient.executeBillQuery(eq("PRD_MO"),
                eq("FBillNo,FDocumentStatus,FProductType,FMaterialId.FNumber,FMaterialId.FName,FLot,FQty,FDate,F_UNW_Text_hp8"),
                anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00081", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01", "客户订单"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00081")).thenReturn(null);

        service.syncFromKingdee("WORK00081", null, null, 50);

        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertEquals("客户订单", captor.getValue().getOrderType());
    }

    @Test
    void orderTypeUnknownValueFallsBackToNull() {
        properties.getMasterData().setOrderTypeField("F_UNW_Text_hp8");
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00082", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("10"),
                        "2026-10-01", "未知值"))));
        when(materialMapper.selectOne(any())).thenReturn(pbc001());
        when(workOrderService.getByWorkOrderNo("WORK00082")).thenReturn(null);

        service.syncFromKingdee("WORK00082", null, null, 50);

        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertNull(captor.getValue().getOrderType());
    }
}
