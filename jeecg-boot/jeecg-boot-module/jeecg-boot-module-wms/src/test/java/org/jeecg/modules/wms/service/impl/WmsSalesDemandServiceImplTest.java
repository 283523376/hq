package org.jeecg.modules.wms.service.impl;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 销售出库待办生成：缺口要扣掉 ERP 侧已关联的出库量。
 *
 * <p>背景（2026-10-30 真账套实测）：发货通知单的剩余可出库量在**审核**时才核销，
 * 草稿期间不减。若只扣 WMS 自己的在途单，ERP 已开但未审的出库单会被漏算 → 重复开单。
 */
class WmsSalesDemandServiceImplTest {

    /** 与 FIELD_KEYS 下标一致：FBillNo,客户码,客户名,交货日期,物料,FQty,FRemainOutQty,FLot,FStockID,FSrcBillNo */
    private static final List<Object> NOTICE_ROW = List.of(
            "FHTZD036453", "03.253", "客户甲", "2026-10-30", "ZA.B026", 2000.0, 2000.0, "L1", "W.17", "XSDD001");

    private WmsKingdeeProperties properties;
    private WmsKingdeeFormClient formClient;
    private WmsSalesDemandServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        properties.setDefaultOrgNumber("101");
        properties.getSalesDelivery().setBatchSize(100);
        formClient = mock(WmsKingdeeFormClient.class);

        WmsKingdeeStockMappingResolver stockMappingResolver = mock(WmsKingdeeStockMappingResolver.class);
        when(stockMappingResolver.resolveWmsWarehouse(anyString())).thenReturn("D.01");
        WmsMaterialCodeResolver materialResolver = mock(WmsMaterialCodeResolver.class);
        when(materialResolver.reverseKingdeeCode(anyString())).thenAnswer(inv -> inv.getArgument(0));

        WmsOutboundOrderMapper outboundMapper = mock(WmsOutboundOrderMapper.class);
        when(outboundMapper.selectList(any())).thenReturn(List.of());
        WmsOutboundDetailMapper detailMapper = mock(WmsOutboundDetailMapper.class);

        IWmsOutboundService outboundService = mock(IWmsOutboundService.class);
        when(outboundService.createSalesFromNotice(anyString(), anyString(), anyString(),
                any(), any(), any())).thenAnswer(inv -> {
                    WmsOutboundOrder created = new WmsOutboundOrder();
                    created.setOutboundNo("XS-TEST");
                    return created;
                });
        service = new WmsSalesDemandServiceImpl(properties, formClient, stockMappingResolver,
                materialResolver, outboundMapper, detailMapper, outboundService);

        when(formClient.executeBillQuery(eq("SAL_DELIVERYNOTICE"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(NOTICE_ROW), null, null));
        // 没有关联出库单时：查询返回空
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(), null, null));
    }

    @Test
    void generate_skipsWhenErpAlreadyHasLinkedOutbound() {
        // ERP 已有一张未审核的关联出库单，占满全部剩余量
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(),
                contains("FSrcBillNo='FHTZD036453'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("FHTZD036453", "ZA.B026", 2000.0, "A")), null, null));

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(0, vo.getCreated(), "ERP 已有关联出库单时不应再生成待办");
        assertTrue(vo.getSkippedReasons().stream().anyMatch(r -> r.contains("ERP 已有关联出库单")),
                "跳过原因应说明是 ERP 已有关联出库单，实际：" + vo.getSkippedReasons());
    }

    @Test
    void generate_createsWhenErpLinkedQtyBelowRemain() {
        // ERP 已关联 500，remain 2000 → 仍应生成 1500 的待办
        when(formClient.executeBillQuery(eq("SAL_OUTSTOCK"), anyString(),
                contains("FSrcBillNo='FHTZD036453'"), anyInt()))
                .thenReturn(WmsKingdeeResult.ok("[]", List.of(List.of("FHTZD036453", "ZA.B026", 500.0, "B")), null, null));

        WmsDemandResultVO vo = service.generatePendingSalesOutbounds();

        assertEquals(1, vo.getCreated(), "ERP 已关联量小于剩余量时仍要生成待办");
    }
}
