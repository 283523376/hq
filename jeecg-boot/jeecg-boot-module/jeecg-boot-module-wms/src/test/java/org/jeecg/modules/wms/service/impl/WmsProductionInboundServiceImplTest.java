package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.dto.WmsProductionSubmitDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsLocationService;
import org.jeecg.modules.wms.service.IWmsSystemConfigService;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库提交单测：工单校验、计划量边界、载具占用、废品入库、库位占用。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionInboundServiceImplTest {

    private WmsWorkOrderMapper workOrderMapper;
    private WmsProductionInboundMapper inboundMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsInventoryMapper inventoryMapper;
    private IWmsLocationService locationService;
    private IWmsSystemConfigService systemConfigService;
    private ISysBaseAPI sysBaseAPI;
    private WmsProductionInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        inventoryMapper = mock(WmsInventoryMapper.class);
        locationService = mock(IWmsLocationService.class);
        systemConfigService = mock(IWmsSystemConfigService.class);
        sysBaseAPI = mock(ISysBaseAPI.class);

        service = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", inboundMapper);
        WmsTestSupport.inject(service, "workOrderMapper", workOrderMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
        WmsTestSupport.inject(service, "warehouseMapper", warehouseMapper);
        WmsTestSupport.inject(service, "inventoryMapper", inventoryMapper);
        WmsTestSupport.inject(service, "locationService", locationService);
        WmsTestSupport.inject(service, "systemConfigService", systemConfigService);
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
    }

    private void stubWorkOrder(String productType, String sum) {
        WmsWorkOrder wo = new WmsWorkOrder()
                .setWorkOrderNo("WO1")
                .setPlanQty(new BigDecimal("100"))
                .setProductType(productType)
                .setMaterialId("mat1")
                .setMaterialCode("M1")
                .setMaterialName("物料一")
                .setBatchNo("B1")
                .setWorkOrderStatus("生产中");
        when(workOrderMapper.selectByNoForUpdate("WO1")).thenReturn(wo);
        when(inboundMapper.sumInboundedQty("WO1")).thenReturn(new BigDecimal(sum));
        when(inboundMapper.insert(any(WmsProductionInbound.class))).thenReturn(1);
        when(locationMapper.update(any(), any())).thenReturn(1);
        WmsLocation freeLocation = new WmsLocation();
        freeLocation.setId("loc1");
        freeLocation.setStatus("空闲");
        when(locationService.listFreeByWarehouseType("立库")).thenReturn(List.of(freeLocation));
        WmsWarehouse warehouse = new WmsWarehouse();
        warehouse.setId("wh1");
        when(warehouseMapper.selectOne(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsWarehouse>>any()))
                .thenReturn(warehouse);
        WmsLocation defaultLocation = new WmsLocation();
        defaultLocation.setId("default-loc");
        when(locationMapper.selectOne(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsLocation>>any()))
                .thenReturn(defaultLocation);
    }

    private WmsProductionSubmitDTO dto(String good, String bad, String carrier) {
        WmsProductionSubmitDTO dto = new WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO1");
        dto.setGoodQty(new BigDecimal(good));
        dto.setBadQty(new BigDecimal(bad));
        dto.setCarrierCode(carrier);
        dto.setBatchNo("B1");
        return dto;
    }

    @Test
    void submit_exceedsPlan_throwsExactMessage() {
        stubWorkOrder("半成品", "95");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.submit(dto("4", "2", "RFID-1")));
        assertEquals("数量超出工单范围", e.getMessage());
        verify(inboundMapper, never()).insert(any(WmsProductionInbound.class));
    }

    @Test
    void submit_exactRemaining_isAccepted() {
        stubWorkOrder("半成品", "95");
        service.submit(dto("4", "1", "RFID-1"));
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getQuantity().compareTo(new BigDecimal("5")));
    }

    /**
     * 模型 B（2026-10-28）：工单不带产品类型——金蝶没有这个概念。
     * 工单作业台提交的是完工入库，按「成品」处理：二维码 + 敏捷仓默认库位。
     */
    @Test
    void submit_withoutProductType_defaultsToFinished() {
        stubWorkOrder(null, "0");
        service.submit(dto("1", "0", "QR-1"));
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).insert(captor.capture());
        WmsProductionInbound created = captor.getValue();
        assertEquals("成品", created.getProductType());
        assertEquals("二维码", created.getIdentType());
        assertEquals("default-loc", created.getLocationId());
    }

    @Test
    void submit_activeCarrier_isRejected() {
        stubWorkOrder("半成品", "0");
        when(inboundMapper.selectActiveByCarrier("RFID-1")).thenReturn(new WmsProductionInbound());
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.submit(dto("1", "0", "RFID-1")));
        assertEquals("载具正在使用中", e.getMessage());
    }

    @Test
    void submit_badQty_writesScrapInventory() {
        stubWorkOrder("半成品", "0");
        service.submit(dto("8", "2", "RFID-1"));
        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(captor.capture());
        assertEquals("default-loc", captor.getValue().getLocationId());
        assertEquals(0, captor.getValue().getQuantity().compareTo(new BigDecimal("2")));
        // container_code 是 NOT NULL：不良品入库必须显式空串，否则真实 SQL 报 1048
        assertEquals("", captor.getValue().getContainerCode());
    }

    @Test
    void submit_semiFinished_locksStereoLocation() {
        stubWorkOrder("半成品", "0");
        service.submit(dto("8", "0", "RFID-1"));
        verify(locationMapper).update(any(), any());
    }

    @Test
    void submit_finished_usesAgileDefaultLocation() {
        stubWorkOrder("成品", "0");
        service.submit(dto("5", "0", "MITA_00101"));
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).insert(captor.capture());
        assertEquals("二维码", captor.getValue().getIdentType());
        assertEquals("default-loc", captor.getValue().getLocationId());
    }

    @Test
    void submit_carrierTooLong_isRejected() {
        stubWorkOrder("半成品", "0");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.submit(dto("1", "0", "X".repeat(65))));
        assertEquals("载具码长度不能超过 64", e.getMessage());
    }

    @Test
    void submit_quantityScaleTooLarge_isRejected() {
        stubWorkOrder("半成品", "0");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.submit(dto("1.0001", "0", "RFID-1")));
        assertEquals("数量精度不能超过 3 位小数", e.getMessage());
    }

    @Test
    void submit_badQty_usesConfiguredScrapWarehouse() {
        stubWorkOrder("半成品", "0");
        when(systemConfigService.getString(eq("defect.warehouse.code"), anyString())).thenReturn("FPC01");
        WmsLocation configured = new WmsLocation();
        configured.setId("scrap-cfg");
        when(locationMapper.selectOne(org.mockito.ArgumentMatchers
                .<com.baomidou.mybatisplus.core.conditions.Wrapper<WmsLocation>>any()))
                .thenReturn(configured);

        service.submit(dto("0", "2", "RFID-1"));

        ArgumentCaptor<WmsInventory> captor = ArgumentCaptor.forClass(WmsInventory.class);
        verify(inventoryMapper).upsertInventory(captor.capture());
        assertEquals("scrap-cfg", captor.getValue().getLocationId());
    }

    @Test
    void submit_duplicateActiveCarrierKey_mapsToUserMessage() {
        stubWorkOrder("半成品", "0");
        when(inboundMapper.insert(any(WmsProductionInbound.class))).thenThrow(
                new org.springframework.dao.DuplicateKeyException(
                        "Duplicate entry for key 'uk_wms_prod_inbound_active_carrier'"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.submit(dto("1", "0", "RFID-1")));
        assertEquals("载具正在使用中", e.getMessage());
    }

    @Test
    void submit_noGoodQty_isInboundImmediately() {
        stubWorkOrder("半成品", "0");
        service.submit(dto("0", "2", "RFID-1"));
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).insert(captor.capture());
        assertEquals("已入库", captor.getValue().getStatus());
        assertEquals("待同步", captor.getValue().getSyncStatus());
        verify(locationService, never()).listFreeByWarehouseType(anyString());
    }

    @Test
    void pendingWorkOrder_rejectedAtGate() {
        org.jeecg.modules.wms.entity.WmsWorkOrder wo = new org.jeecg.modules.wms.entity.WmsWorkOrder()
                .setWorkOrderNo("WO1").setWorkOrderStatus("待生产").setPlanQty(new BigDecimal("10"));
        when(workOrderMapper.selectByNoForUpdate("WO1")).thenReturn(wo);
        org.jeecg.modules.wms.dto.WmsProductionSubmitDTO dto = new org.jeecg.modules.wms.dto.WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO1");
        dto.setGoodQty(BigDecimal.ONE);
        dto.setBadQty(BigDecimal.ZERO);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.submit(dto));
        assertEquals("工单状态不允许入库", e.getMessage());
    }

    @Test
    void releasedWorkOrder_passesGate() {
        org.jeecg.modules.wms.entity.WmsWorkOrder wo = new org.jeecg.modules.wms.entity.WmsWorkOrder()
                .setWorkOrderNo("WO2").setWorkOrderStatus("已下达").setPlanQty(new BigDecimal("10"));
        when(workOrderMapper.selectByNoForUpdate("WO2")).thenReturn(wo);
        org.jeecg.modules.wms.dto.WmsProductionSubmitDTO dto = new org.jeecg.modules.wms.dto.WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO2");
        dto.setGoodQty(BigDecimal.ONE);
        dto.setBadQty(BigDecimal.ZERO);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.submit(dto));
        assertTrue(!"工单状态不允许入库".equals(e.getMessage()),
                "已下达工单应通过门槛，实际被拦：" + e.getMessage());
    }

    /** 已作废工单不能再提交入库，否则纠正完又产生新错误。 */
    @Test
    void cancelledWorkOrder_rejectedAtGate() {
        org.jeecg.modules.wms.entity.WmsWorkOrder wo = new org.jeecg.modules.wms.entity.WmsWorkOrder()
                .setWorkOrderNo("WO-CANCEL").setWorkOrderStatus("已作废").setPlanQty(new BigDecimal("10"));
        when(workOrderMapper.selectByNoForUpdate("WO-CANCEL")).thenReturn(wo);
        org.jeecg.modules.wms.dto.WmsProductionSubmitDTO dto = new org.jeecg.modules.wms.dto.WmsProductionSubmitDTO();
        dto.setWorkOrderNo("WO-CANCEL");
        dto.setGoodQty(BigDecimal.ONE);
        dto.setBadQty(BigDecimal.ZERO);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.submit(dto));
        assertEquals("工单已作废，不能提交入库", e.getMessage());
    }
}
