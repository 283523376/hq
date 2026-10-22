package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.jeecg.modules.wms.vo.WmsWorkOrderInfoVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        service = new WmsKingdeeWorkOrderSyncServiceImpl(
                formClient, properties, workOrderService, materialMapper, syncLogService);
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
}
