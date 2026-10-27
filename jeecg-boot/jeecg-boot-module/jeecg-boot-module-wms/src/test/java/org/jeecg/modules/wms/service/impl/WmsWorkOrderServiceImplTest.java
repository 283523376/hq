package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产工单扫码查询、推送校验与删除守卫单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsWorkOrderServiceImplTest {

    private WmsWorkOrderMapper mapper;
    private WmsMaterialMapper materialMapper;
    private WmsProductionInboundMapper inboundMapper;
    private WmsWorkOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(WmsWorkOrderMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        service = new WmsWorkOrderServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", mapper);
        WmsTestSupport.inject(service, "materialMapper", materialMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
    }

    @Test
    void scan_missingWorkOrder_throwsInvalidMessage() {
        when(mapper.selectOne(any())).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scan("NOPE"));
        assertEquals("工单号无效", e.getMessage());
    }

    @Test
    void scan_returnsRemainingQty() {
        WmsWorkOrder wo = new WmsWorkOrder().setWorkOrderNo("WO1").setPlanQty(new BigDecimal("100"));
        when(mapper.selectOne(any())).thenReturn(wo);
        when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal("30"));
        assertEquals(0, service.scan("WO1").getRemainingQty().compareTo(new BigDecimal("70")));
    }

    // ---------------------------------------------------------------- BD-002 V2.2 push 合并规则

    private WmsWorkOrderPushDTO baseDto() {
        WmsWorkOrderPushDTO dto = new WmsWorkOrderPushDTO();
        dto.setWorkOrderNo("WO1");
        dto.setMaterialCode("M1");
        dto.setProductType("半成品");
        dto.setPlanQty(new BigDecimal("10"));
        dto.setBatchNo("B1");
        return dto;
    }

    private WmsWorkOrder capturedOnSave() {
        org.mockito.ArgumentCaptor<WmsWorkOrder> captor =
                org.mockito.ArgumentCaptor.forClass(WmsWorkOrder.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    private WmsWorkOrder capturedOnUpdate() {
        org.mockito.ArgumentCaptor<WmsWorkOrder> captor =
                org.mockito.ArgumentCaptor.forClass(WmsWorkOrder.class);
        org.mockito.Mockito.verify(mapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void pushUpdateKeepsErpSourceAndMergesStatus() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("生产中"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("已下达");
        dto.setErpDocumentStatus("C");
        service.push(dto);
        WmsWorkOrder saved = capturedOnUpdate();
        assertEquals("ERP", saved.getSource());
        assertEquals("生产中", saved.getWorkOrderStatus());
        assertEquals("C", saved.getErpDocumentStatus());
    }

    @Test
    void pushUpgradesPendingToReleased() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("待生产"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("ERP");
        dto.setWorkOrderStatus("已下达");
        service.push(dto);
        assertEquals("已下达", capturedOnUpdate().getWorkOrderStatus());
    }

    @Test
    void pushNeverReopensClosed() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已关闭"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        service.push(dto);
        assertEquals("已关闭", capturedOnUpdate().getWorkOrderStatus());
    }

    @Test
    void pushCreateDefaultsMesSource_andErpCreateIsExplicit() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(null);
        service.push(baseDto());
        WmsWorkOrder created = capturedOnSave();
        assertEquals("MES", created.getSource());
        assertEquals("生产中", created.getWorkOrderStatus());

        org.mockito.Mockito.clearInvocations(mapper);
        when(mapper.selectOne(any())).thenReturn(null);
        WmsWorkOrderPushDTO erp = baseDto();
        erp.setSource("ERP");
        erp.setWorkOrderStatus("已下达");
        service.push(erp);
        WmsWorkOrder erpCreated = capturedOnSave();
        assertEquals("ERP", erpCreated.getSource());
        assertEquals("已下达", erpCreated.getWorkOrderStatus());
    }

    @Test
    void pushMesUpdateKeepsErpDocumentStatus() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已下达").setErpDocumentStatus("C"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        service.push(dto);
        assertEquals("C", capturedOnUpdate().getErpDocumentStatus());
    }

    @Test
    void pushMesUpdateKeepsErpOrderType() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已下达").setErpDocumentStatus("C")
                .setOrderType("备库订单"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        dto.setOrderType(null);
        service.push(dto);
        assertEquals("备库订单", capturedOnUpdate().getOrderType());
    }

    @Test
    void pushFromErpMarksSyncSuccess() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("待生产").setSyncStatus("同步失败")
                .setRetryCount(2).setLastSyncError("旧错误"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("ERP");
        dto.setWorkOrderStatus("已下达");
        dto.setErpDocumentStatus("C");
        service.push(dto);
        WmsWorkOrder saved = capturedOnUpdate();
        assertEquals("同步成功", saved.getSyncStatus());
        assertEquals(0, saved.getRetryCount().intValue());
        assertEquals(null, saved.getLastSyncError());
        org.junit.jupiter.api.Assertions.assertNotNull(saved.getLastSyncTime());
    }

    @Test
    void pushFromMesDoesNotTouchSyncFields() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP")
                .setWorkOrderStatus("已下达").setSyncStatus("同步成功"));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("生产中");
        service.push(dto);
        assertEquals("同步成功", capturedOnUpdate().getSyncStatus());
    }

    @Test
    void markSyncFailedIncrementsCounters() {
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSyncStatus("同步成功")
                .setRetryCount(1).setSyncFailCount(2));
        service.markSyncFailed("WO1", "字段缺失");
        WmsWorkOrder saved = capturedOnUpdate();
        assertEquals("同步失败", saved.getSyncStatus());
        assertEquals(2, saved.getRetryCount().intValue());
        assertEquals(3, saved.getSyncFailCount().intValue());
        assertEquals("字段缺失", saved.getLastSyncError());
    }

    @Test
    void pushRejectsClosedStatusFromExternalSource() {
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("MES");
        dto.setWorkOrderStatus("已关闭");
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertTrue(e.getMessage().contains("只能由WMS关单"));
    }

    @Test
    void markSyncFailedTruncatesLongError() {
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setRetryCount(0).setSyncFailCount(0));
        String longError = "x".repeat(600);
        service.markSyncFailed("WO1", longError);
        assertEquals(500, capturedOnUpdate().getLastSyncError().length());
    }

    @Test
    void pushDuplicateInsertRetriesAsUpdate() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        WmsWorkOrder existing = new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("ERP").setWorkOrderStatus("已下达");
        when(mapper.selectOne(any())).thenReturn(null).thenReturn(existing);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException("dup"))
                .when(mapper).insert(org.mockito.ArgumentMatchers.any(WmsWorkOrder.class));
        WmsWorkOrderPushDTO dto = baseDto();
        dto.setSource("ERP");
        dto.setWorkOrderStatus("已下达");
        dto.setErpDocumentStatus("C");

        WmsWorkOrder result = service.push(dto);

        assertEquals("WO1", result.getWorkOrderNo());
        org.mockito.Mockito.verify(mapper).updateById(org.mockito.ArgumentMatchers.any(WmsWorkOrder.class));
    }

    // ------------------------------------------------- 字段长度/精度守卫（对齐 DB 列宽，避免 500）

    /** 物料查得到、工单已存在，让 push 走到 validate()。 */
    private WmsWorkOrderPushDTO pushableDto() {
        WmsMaterial material = new WmsMaterial();
        material.setId("m1");
        material.setMaterialCode("M1");
        material.setMaterialName("物料");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("MES").setWorkOrderStatus("生产中"));
        return baseDto();
    }

    @Test
    void push_remarkTooLong_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setRemark("字".repeat(501));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("备注长度不能超过 500", e.getMessage());
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .updateById(any(WmsWorkOrder.class));
    }

    @Test
    void push_batchNoTooLong_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setBatchNo("B".repeat(65));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("批次号长度不能超过 64", e.getMessage());
    }

    @Test
    void push_mesOrderNoTooLong_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setMesOrderNo("M".repeat(65));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("MES订单号长度不能超过 64", e.getMessage());
    }

    @Test
    void push_planQtyWithTooManyDecimals_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setPlanQty(new BigDecimal("1.2345"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("计划数量精度不能超过 3 位小数", e.getMessage());
    }

    @Test
    void push_planQtyBeyondColumnRange_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setPlanQty(new BigDecimal("1000000000"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("计划数量超出可录入范围", e.getMessage());
    }

    // ------------------------------------------- 已投产工单的推送守卫（防覆盖生产事实）

    private WmsWorkOrderPushDTO pushableDtoWithInbounded(String inbounded) {
        WmsWorkOrderPushDTO dto = pushableDto();
        when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal(inbounded));
        return dto;
    }

    @Test
    void push_lowerPlanQtyBelowInbounded_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDtoWithInbounded("8");
        // 物料/产品类型未变，只有计划量被调低——单独验证「计划量不得低于已入库量」这条守卫
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("MES").setWorkOrderStatus("生产中")
                .setMaterialId("m1").setProductType("半成品"));
        dto.setPlanQty(new BigDecimal("5"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertTrue(e.getMessage().startsWith("计划数量不能小于已入库数量"));
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                .updateById(any(WmsWorkOrder.class));
    }

    @Test
    void push_changeMaterialAfterInbound_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDtoWithInbounded("8");
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("MES").setWorkOrderStatus("生产中")
                .setMaterialId("other-material"));
        dto.setPlanQty(new BigDecimal("100"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("工单已投料/入库，不允许变更物料或产品类型", e.getMessage());
    }

    @Test
    void push_changeProductTypeAfterInbound_isRejected() {
        WmsWorkOrderPushDTO dto = pushableDtoWithInbounded("8");
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("MES").setWorkOrderStatus("生产中")
                .setMaterialId("m1").setProductType("成品"));
        dto.setPlanQty(new BigDecimal("100"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.push(dto));
        assertEquals("工单已投料/入库，不允许变更物料或产品类型", e.getMessage());
    }

    /** 未投产时改物料/计划量是正常的工程变更，不得误拦。 */
    @Test
    void push_changeMaterialBeforeAnyInbound_isAccepted() {
        WmsWorkOrderPushDTO dto = pushableDtoWithInbounded("0");
        when(mapper.selectOne(any())).thenReturn(new WmsWorkOrder()
                .setWorkOrderNo("WO1").setSource("MES").setWorkOrderStatus("生产中")
                .setMaterialId("other-material").setProductType("成品"));
        dto.setPlanQty(new BigDecimal("100"));

        service.push(dto);

        assertEquals("m1", capturedOnUpdate().getMaterialId());
    }

    /** 尾部 0 不算精度：ERP 常见 {@code 10.000000} 必须放行。 */
    @Test
    void push_planQtyWithTrailingZeros_isAccepted() {
        WmsWorkOrderPushDTO dto = pushableDto();
        dto.setPlanQty(new BigDecimal("10.000000"));

        service.push(dto);

        assertEquals(0, capturedOnUpdate().getPlanQty().compareTo(new BigDecimal("10")));
    }
}
