package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsAuditEvent;
import org.jeecg.modules.wms.dto.WmsOutboundDetailDTO;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
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
        // 生成侧会回查金蝶已关联的出库量；默认无关联单
        // 关联量查询走「startRow + 稳定排序」的翻页重载
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
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

    @Test
    void generate_subtractsErpLinkedUnapproved_forPartialShipment() {
        // 金蝶已开一张未审核出库单 30：通知单 remain 还是 50（审核才核销），缺口应为 20
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn("MJ");
        when(materialResolver.reverseKingdeeCode("Y.S.002.0085")).thenReturn("Y.S.002.0085");
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("FHTZD1", "Y.S.002.0085", 30.0, "A")), null, null));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("XS3");
        when(outboundService.createSalesFromNotice(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.generatePendingSalesOutbounds();

        ArgumentCaptor<List<WmsOutboundDetailDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundService).createSalesFromNotice(any(), any(), any(), any(), any(), captor.capture());
        assertEquals(0, new BigDecimal("20").compareTo(captor.getValue().get(0).getRequiredQty()),
                "缺口 = remain 50 - ERP 已关联 30 = 20");
    }

    @Test
    void generate_erpLinkedDeductedOnce_forSameMaterialAcrossLines() {
        // 同一物料在通知单上分两行（不同批次）：ERP 已关联 30 只能扣一次，不能每行扣一次
        stubRows(
                row("FHTZD1", "Y.S.002.0085", "50", "S.05"),
                row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn("MJ");
        when(materialResolver.reverseKingdeeCode("Y.S.002.0085")).thenReturn("Y.S.002.0085");
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("FHTZD1", "Y.S.002.0085", 30.0, "A")), null, null));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("XS4");
        when(outboundService.createSalesFromNotice(any(), any(), any(), any(), any(), any())).thenReturn(created);

        service.generatePendingSalesOutbounds();

        ArgumentCaptor<List<WmsOutboundDetailDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboundService).createSalesFromNotice(any(), any(), any(), any(), any(), captor.capture());
        BigDecimal total = captor.getValue().stream()
                .map(WmsOutboundDetailDTO::getRequiredQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("70").compareTo(total),
                "两行合计 remain 100 - ERP 已关联 30 = 70，不能扣两次");
    }

    @Test
    void generate_conservativeSkipWhenErpLinkedQtyIsZero() {
        // 手工在金蝶建的草稿可能只填了数量、实发为空 → FRealQty=0。
        // 当成「未占用」会生成一张必然同步失败的待办，所以按「占用未知」保守跳过
        stubRows(row("FHTZD1", "Y.S.002.0085", "50", "S.05"));
        when(stockMappingResolver.resolveWmsWarehouse("S.05")).thenReturn("MJ");
        when(materialResolver.reverseKingdeeCode("Y.S.002.0085")).thenReturn("Y.S.002.0085");
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt(),
                anyInt(), anyString()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("FHTZD1", "Y.S.002.0085", 0.0, "A")), null, null));

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(0, vo.getCreated(), "ERP 已有关联单但数量未知时不能生成待办");
        assertTrue(vo.getSkippedReasons().stream().anyMatch(r -> r.contains("数量未知")),
                "跳过原因要说明占用数量未知，实际：" + vo.getSkippedReasons());
    }

    /** 可选依赖是字段注入，单测里手动塞进去。 */
    private static void injectAudit(Object target, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField("auditLogService");
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 「错通知单生成了错出库单」是最典型的错数据链路：出库单号 + 通知单号都要能反查。 */
    @Test
    void audit_recordsGeneratedOrderWithNoticeNo() throws Exception {
        IWmsAuditLogService auditService = mock(IWmsAuditLogService.class);
        when(auditService.snapshot(any(), any(String[].class))).thenReturn("{\"outboundNo\":\"XSCK1\"}");
        injectAudit(service, auditService);
        stubRows(row("XSFH001", "M1", "10", "CK-01"));
        when(stockMappingResolver.resolveWmsWarehouse(anyString())).thenReturn("CK-01");
        when(materialResolver.resolveProductType(anyString())).thenReturn(null);
        when(outboundService.createSalesFromNotice(anyString(), anyString(), anyString(), any(),
                any(), any())).thenReturn(new WmsOutboundOrder().setOutboundNo("XSCK1"));

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(1, vo.getCreated(), vo.getMessage());
        ArgumentCaptor<WmsAuditEvent> captor = ArgumentCaptor.forClass(WmsAuditEvent.class);
        verify(auditService).record(captor.capture());
        WmsAuditEvent event = captor.getValue();
        assertEquals("出库单", event.getBizType());
        assertEquals("XSCK1", event.getBizNo());
        assertEquals("XSFH001", event.getBizRef(), "必须能按发货通知单反查它生成了哪些出库单");
        assertEquals("创建", event.getAction());
        assertEquals("ERP同步", event.getChannel());
    }
}
