package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsSubcontractSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 委外订单 → 外协出库只读草稿（spec 2026-10-26 §5.1）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsSubcontractOrderSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private IWmsSyncLogService syncLogService;
    private WmsSubcontractOrderSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        syncLogService = mock(IWmsSyncLogService.class);
        service = new WmsSubcontractOrderSyncServiceImpl(
                formClient, properties, outboundOrderMapper, syncLogService);
    }

    @Test
    void shouldRefuseWhenOrgFilterBlank() {
        WmsSubcontractSyncResultVO vo = service.syncFromKingdee(50);

        assertEquals(0, vo.getQueried());
        assertTrue(vo.getMessages().stream().anyMatch(m -> m.contains("组织")), vo.getMessages().toString());
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldFilterReleasedOrdersWithConfiguredOrgFragment() {
        properties.getSubcontract().setOrgFilter("FPrdOrgId.FNumber='101'");
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", new ArrayList<>(), null, null));

        service.syncFromKingdee(50);

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(anyString(), anyString(), filterCaptor.capture(), anyInt());
        assertTrue(filterCaptor.getValue().contains("FPrdOrgId.FNumber='101'"), filterCaptor.getValue());
        assertTrue(filterCaptor.getValue().contains("FDocumentStatus='C'"), filterCaptor.getValue());
    }

    @Test
    void shouldCreateReadOnlyDraftWithoutDetails() {
        properties.getSubcontract().setOrgFilter("FPrdOrgId.FNumber='101'");
        when(outboundOrderMapper.selectOne(any())).thenReturn(null);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("WWJG001", "C", "普通委外订单",
                        "2026-10-01", "WA.2206.2201.02000430.A003", 59.0, "1")), null, null));

        WmsSubcontractSyncResultVO vo = service.syncFromKingdee(50);

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(outboundOrderMapper).insert(captor.capture());
        WmsOutboundOrder draft = captor.getValue();
        assertEquals(WmsConstants.OUTBOUND_TYPE_SUBCONTRACT, draft.getOutboundType());
        assertEquals(WmsConstants.OUTBOUND_STATUS_DRAFT, draft.getStatus());
        assertEquals("WWJG001", draft.getErpSubOrderNo());
        assertTrue(draft.getOutboundNo().contains("WWJG001"), draft.getOutboundNo());
    }

    @Test
    void shouldUpdateExistingDraftInsteadOfDuplicating() {
        properties.getSubcontract().setOrgFilter("FPrdOrgId.FNumber='101'");
        WmsOutboundOrder existing = new WmsOutboundOrder();
        existing.setId("ob-1");
        existing.setErpSubOrderNo("WWJG002");
        when(outboundOrderMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("WWJG002", "C", "普通委外订单",
                        "2026-10-01", "M1", 10.0, "1")), null, null));

        WmsSubcontractSyncResultVO vo = service.syncFromKingdee(50);

        assertEquals(1, vo.getUpdated());
        assertEquals(0, vo.getCreated());
        verify(outboundOrderMapper).updateById(any(WmsOutboundOrder.class));
        verify(outboundOrderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    @Test
    void shouldVoidDraftWhenPickStatusMatchesConfiguredDoneValue() {
        properties.getSubcontract().setOrgFilter("FPrdOrgId.FNumber='101'");
        properties.getSubcontract().setPickStatusDone("3");
        WmsOutboundOrder existing = new WmsOutboundOrder();
        existing.setId("ob-2");
        existing.setErpSubOrderNo("WWJG003");
        existing.setStatus(WmsConstants.OUTBOUND_STATUS_DRAFT);
        when(outboundOrderMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rows(List.of("WWJG003", "C", "普通委外订单",
                        "2026-10-01", "M1", 10.0, "3")), null, null));

        WmsSubcontractSyncResultVO vo = service.syncFromKingdee(50);

        assertEquals(1, vo.getVoided());
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(outboundOrderMapper).updateById(captor.capture());
        assertEquals(WmsConstants.OUTBOUND_STATUS_CLOSED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getCloseReason().contains("领料"));
    }

    private static List<List<Object>> rows(List<Object> row) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    @Test
    void shouldGroupEntriesByBillAndNotVoidOnPartialPick() {
        properties.getSubcontract().setOrgFilter("FPrdOrgId.FNumber='101'");
        properties.getSubcontract().setPickStatusDone("3");
        WmsOutboundOrder existing = new WmsOutboundOrder();
        existing.setId("ob-9");
        existing.setErpSubOrderNo("WWJG009");
        existing.setStatus(WmsConstants.OUTBOUND_STATUS_DRAFT);
        when(outboundOrderMapper.selectOne(any())).thenReturn(existing);
        when(formClient.executeBillQuery(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", rowsOf(
                        List.of("WWJG009", "C", "普通委外订单", "2026-10-01", "M1", 10.0, "3"),
                        List.of("WWJG009", "C", "普通委外订单", "2026-10-01", "M2", 5.0, "1")), null, null));

        WmsSubcontractSyncResultVO vo = service.syncFromKingdee(50);

        // 一分录一行 → 聚合成 1 单；部分领料不算整单完成
        assertEquals(1, vo.getQueried());
        assertEquals(0, vo.getVoided());
        assertEquals(WmsConstants.OUTBOUND_STATUS_DRAFT, existing.getStatus());
    }

    private static List<List<Object>> rowsOf(List<Object>... items) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> item : items) {
            out.add(new ArrayList<>(item));
        }
        return out;
    }
}
