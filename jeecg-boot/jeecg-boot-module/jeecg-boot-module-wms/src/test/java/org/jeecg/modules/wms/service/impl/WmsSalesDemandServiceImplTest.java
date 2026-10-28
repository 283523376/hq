package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.sync.WmsKingdeeStockMappingResolver;
import org.jeecg.modules.wms.sync.WmsMaterialCodeResolver;
import org.jeecg.modules.wms.vo.WmsDemandResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 销售出库待办单测：只认 C、仓映射预留接口未命中即跳过、在途扣减、分批发货。
 */
class WmsSalesDemandServiceImplTest {

    private WmsKingdeeProperties properties;
    private WmsKingdeeFormClient formClient;
    private WmsKingdeeStockMappingResolver stockMappingResolver;
    private WmsMaterialCodeResolver materialResolver;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private IWmsOutboundService outboundService;
    private WmsSalesDemandServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setDefaultOrgNumber("101");
        formClient = mock(WmsKingdeeFormClient.class);
        stockMappingResolver = mock(WmsKingdeeStockMappingResolver.class);
        materialResolver = mock(WmsMaterialCodeResolver.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        outboundService = mock(IWmsOutboundService.class);
        service = new WmsSalesDemandServiceImpl(properties, formClient, stockMappingResolver,
                materialResolver, outboundMapper, outboundDetailMapper, outboundService);
        when(outboundMapper.selectList(any())).thenReturn(List.of());
    }

    /** 发货通知单行：FBillNo,客户编码,客户名称,要货日期,物料,数量,未出库,批号,仓库,源单 */
    private List<Object> row(String billNo, String material, String remain, String stock) {
        return Arrays.asList(billNo, "01.163", "客户A", "2026-10-30T00:00:00", material,
                remain, remain, null, stock, "XSDD003944");
    }

    private void stubRows(List<Object>... rows) {
        List<List<Object>> data = new ArrayList<>();
        for (List<Object> r : rows) {
            data.add(r);
        }
        when(formClient.executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", data, null, null));
    }

    @Test
    void generate_onlyAuditedAndRemainPositive_filterUsed() {
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn(null);

        service.generatePendingSalesOutbounds();

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(formClient).executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), filter.capture(), anyInt());
        // 用户口径：等 C 才生成
        assertTrue(filter.getValue().contains("FDocumentStatus='C'"), filter.getValue());
        assertTrue(filter.getValue().contains("FRemainOutQty>0"), filter.getValue());
        assertTrue(filter.getValue().contains("FSaleOrgId.FNumber='101'"), filter.getValue());
    }

    @Test
    void generate_skipsWhenWarehouseMappingMissing() {
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn(null);

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getSkippedReasons().get(0).contains("stock-map"), vo.getSkippedReasons().get(0));
        verify(outboundService, never()).createSalesFromNotice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void generate_createsOrderWhenMappingPresent() {
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn("MJ");
        when(materialResolver.reverseKingdeeCode("Y.S.002.0085")).thenReturn("Y.S.002.0085");
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("XS1");
        when(outboundService.createSalesFromNotice(any(), any(), any(), any(), any(), any())).thenReturn(created);

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(1, vo.getCreated());
        assertEquals(List.of("XS1"), vo.getCreatedOrders());
        ArgumentCaptor<List<WmsOutboundDetailDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundService).createSalesFromNotice(eq("FHTZD1"), eq("01.163"), eq("客户A"), any(), any(), captor.capture());
        assertEquals(0, new BigDecimal("50").compareTo(captor.getValue().get(0).getRequiredQty()));
    }

    @Test
    void generate_subtractsInFlight_forPartialShipment() {
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn("MJ");
        when(materialResolver.reverseKingdeeCode("Y.S.002.0085")).thenReturn("Y.S.002.0085");
        WmsOutboundOrder open = new WmsOutboundOrder();
        open.setId("o1");
        when(outboundMapper.selectList(any())).thenReturn(List.of(open));
        WmsOutboundDetail line = new WmsOutboundDetail();
        line.setMaterialCode("Y.S.002.0085");
        line.setRequiredQty(new BigDecimal("20"));
        when(outboundDetailMapper.selectList(any())).thenReturn(List.of(line));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("XS2");
        when(outboundService.createSalesFromNotice(any(), any(), any(), any(), any(), any())).thenReturn(created);

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<List<WmsOutboundDetailDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundService).createSalesFromNotice(any(), any(), any(), any(), any(), captor.capture());
        // 分批发货：50 - 20 = 30
        assertEquals(0, new BigDecimal("30").compareTo(captor.getValue().get(0).getRequiredQty()));
    }

    @Test
    void generate_skipsWhenKingdeeDisabled() {
        properties.setEnabled(false);
        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();
        assertTrue(vo.getMessage().contains("跳过"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void generate_skipsWhenOrgNotConfigured() {
        properties.setDefaultOrgNumber("");
        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();
        assertTrue(vo.getMessage().contains("销售组织"));
        verify(formClient, never()).executeBillQuery(anyString(), anyString(), anyString(), anyInt());
    }
}
