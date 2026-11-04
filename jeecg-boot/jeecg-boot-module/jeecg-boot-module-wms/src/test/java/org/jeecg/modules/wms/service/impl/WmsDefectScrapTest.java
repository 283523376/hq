package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsInventoryLedgerQueryDTO;
import org.jeecg.modules.wms.dto.WmsScrapDTO;
import org.jeecg.modules.wms.entity.WmsInventory;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsAuditLogService;
import org.jeecg.modules.wms.vo.WmsScrapResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: XT-001 报废出库单测。
 *
 * <p>报废是**减库存的不可逆动作**，三条底线：数量不能超可用量、只能报废废品仓的货、
 * 扣减失败（并发抢走）必须整体回滚而不是留下半张单。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-02
 */
class WmsDefectScrapTest {

    private WmsInventoryMapper inventoryMapper;
    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundDetailMapper detailMapper;
    private WmsLocationMapper locationMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsProductionInboundMapper inboundMapper;
    private WmsMaterialMapper materialMapper;
    private IWmsAuditLogService auditLogService;
    private WmsDefectServiceImpl service;

    @BeforeEach
    void setUp() {
        inventoryMapper = mock(WmsInventoryMapper.class);
        orderMapper = mock(WmsOutboundOrderMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        auditLogService = mock(IWmsAuditLogService.class);
        // RK-005 扩展依赖：两类不良来源与退货（本测试类只覆盖 XT-001 报废，传 mock 占位即可）
        service = new WmsDefectServiceImpl(inventoryMapper, orderMapper, detailMapper,
                locationMapper, warehouseMapper, inboundMapper, materialMapper, auditLogService,
                mock(org.jeecg.modules.wms.mapper.WmsDefectRecordMapper.class),
                mock(org.jeecg.modules.wms.mapper.WmsInboundOrderMapper.class),
                mock(org.jeecg.modules.wms.service.IWmsCustomerService.class),
                mock(org.jeecg.modules.wms.sync.WmsWarehouseCodeResolver.class),
                mock(org.jeecg.modules.wms.sync.IWmsKingdeePurchaseReturnWriter.class),
                mock(org.jeecg.modules.wms.sync.WmsKingdeeFormClient.class),
                mock(org.jeecg.modules.wms.service.IWmsSystemConfigService.class),
                mock(org.jeecg.common.system.api.ISysBaseAPI.class));
        WmsMaterial m = new WmsMaterial().setMaterialCode("M001").setMaterialName("螺丝").setUnit("个");
        m.setId("MAT1");
        when(materialMapper.selectById(anyString())).thenReturn(m);
    }

    private WmsInventory inv(String id, String locationId, String qty) {
        WmsInventory i = new WmsInventory().setLocationId(locationId).setMaterialId("MAT1")
                .setQuantity(new BigDecimal(qty)).setLockedQty(BigDecimal.ZERO);
        i.setId(id);
        return i;
    }

    private WmsLocation scrapLocation() {
        WmsLocation l = new WmsLocation().setWarehouseId("W-SCRAP");
        l.setId("L-SCRAP");
        return l;
    }

    private WmsWarehouse scrapWarehouse() {
        WmsWarehouse w = new WmsWarehouse().setWarehouseType(WmsConstants.WAREHOUSE_TYPE_SCRAP);
        w.setId("W-SCRAP");
        return w;
    }

    private void stubScrapWarehouse() {
        when(locationMapper.selectById("L-SCRAP")).thenReturn(scrapLocation());
        when(warehouseMapper.selectById("W-SCRAP")).thenReturn(scrapWarehouse());
    }

    /** 报废成功：扣库存 + 建单（一步到位已完成）+ 写明细 + 留审计。 */
    @Test
    void scrap_deductsInventoryAndCreatesOrder() {
        when(inventoryMapper.selectById("I1")).thenReturn(inv("I1", "L-SCRAP", "10"));
        stubScrapWarehouse();
        when(inventoryMapper.deductQuantity("I1", new BigDecimal("3"))).thenReturn(1);

        WmsScrapResultVO vo = service.scrap(new WmsScrapDTO()
                .setInventoryId("I1").setQuantity(new BigDecimal("3")).setReason("来料报废"));

        assertEquals(new BigDecimal("7"), vo.getRemainingQty());
        assertEquals("报废出库", vo.getOutboundType());

        ArgumentCaptor<WmsOutboundOrder> orderCaptor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals(WmsConstants.OUTBOUND_TYPE_SCRAP, orderCaptor.getValue().getOutboundType());
        assertEquals(WmsConstants.OUTBOUND_STATUS_COMPLETED, orderCaptor.getValue().getStatus());
        assertTrue(orderCaptor.getValue().getOutboundNo().startsWith(WmsConstants.OUTBOUND_NO_PREFIX_SCRAP));

        ArgumentCaptor<WmsOutboundDetail> detailCaptor = ArgumentCaptor.forClass(WmsOutboundDetail.class);
        verify(detailMapper).insert(detailCaptor.capture());
        assertEquals(new BigDecimal("3"), detailCaptor.getValue().getActualQty());

        // 报废必须留痕（谁在何时报废了多少）
        verify(auditLogService).record(any());
    }

    /** 超量报废：明确拒绝，不建单、不扣库存。 */
    @Test
    void scrap_exceedsAvailable_rejected() {
        when(inventoryMapper.selectById("I1")).thenReturn(inv("I1", "L-SCRAP", "10"));
        stubScrapWarehouse();

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scrap(
                new WmsScrapDTO().setInventoryId("I1").setQuantity(new BigDecimal("11"))));

        assertTrue(e.getMessage().contains("超过可用数量"), e.getMessage());
        verify(inventoryMapper, never()).deductQuantity(anyString(), any());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    /** 已锁定部分不可报废：可用量 = 数量 - 锁定。 */
    @Test
    void scrap_lockedQty_notAvailable() {
        WmsInventory i = inv("I1", "L-SCRAP", "10");
        i.setLockedQty(new BigDecimal("8"));
        when(inventoryMapper.selectById("I1")).thenReturn(i);
        stubScrapWarehouse();

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scrap(
                new WmsScrapDTO().setInventoryId("I1").setQuantity(new BigDecimal("3"))));

        assertTrue(e.getMessage().contains("超过可用数量"), e.getMessage());
    }

    /** 非废品仓的库存不能报废：防止误伤正常库存。 */
    @Test
    void scrap_nonScrapWarehouse_rejected() {
        when(inventoryMapper.selectById("I1")).thenReturn(inv("I1", "L-NORMAL", "10"));
        WmsLocation normal = new WmsLocation().setWarehouseId("W-NORMAL");
        normal.setId("L-NORMAL");
        when(locationMapper.selectById("L-NORMAL")).thenReturn(normal);
        WmsWarehouse w = new WmsWarehouse().setWarehouseType("普通仓");
        w.setId("W-NORMAL");
        when(warehouseMapper.selectById("W-NORMAL")).thenReturn(w);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scrap(
                new WmsScrapDTO().setInventoryId("I1").setQuantity(BigDecimal.ONE)));

        assertTrue(e.getMessage().contains("废品仓"), e.getMessage());
        verify(inventoryMapper, never()).deductQuantity(anyString(), any());
    }

    /** 并发抢货：扣减影响行数 0 → 抛错让事务回滚，不留半张单。 */
    @Test
    void scrap_deductAffectsZero_rollsBack() {
        when(inventoryMapper.selectById("I1")).thenReturn(inv("I1", "L-SCRAP", "10"));
        stubScrapWarehouse();
        when(inventoryMapper.deductQuantity(anyString(), any())).thenReturn(0);

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scrap(
                new WmsScrapDTO().setInventoryId("I1").setQuantity(BigDecimal.ONE)));

        assertTrue(e.getMessage().contains("库存已变更") || e.getMessage().contains("请重试"), e.getMessage());
    }

    /** 数量必须为正。 */
    @Test
    void scrap_nonPositiveQty_rejected() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.scrap(
                new WmsScrapDTO().setInventoryId("I1").setQuantity(BigDecimal.ZERO)));
        assertTrue(e.getMessage().contains("数量"), e.getMessage());
        verify(orderMapper, never()).insert(any(WmsOutboundOrder.class));
    }

    /** 台账只显示废品仓的库存，正常仓不混进来。 */
    @Test
    void ledger_onlyShowsScrapWarehouse() {
        WmsWarehouse scrap = scrapWarehouse();
        when(warehouseMapper.selectList(any())).thenReturn(List.of(scrap));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());

        service.pageLedger(1, 10, null, null, null, null, null);

        // 必须按「废品仓」这个条件去查库区，而不是把所有库存都捞出来
        verify(warehouseMapper).selectList(any());
        verify(inventoryMapper).selectLedgerPage(any(), any());
    }

    /** 受托不良入废品仓后仍记 CONSIGNED：必须能按库存类型/受托方筛出来，否则永远无法在模块内处置。 */
    @Test
    void ledger_consignedFilterPassesOwnershipAndConsignor() {
        when(warehouseMapper.selectList(any())).thenReturn(List.of(scrapWarehouse()));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());

        service.pageLedger(1, 10, null, null, null, WmsConstants.OWNERSHIP_CONSIGNED, " sup1 ");

        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor =
                ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(inventoryMapper).selectLedgerPage(any(), captor.capture());
        assertEquals(WmsConstants.OWNERSHIP_CONSIGNED, captor.getValue().getOwnershipType());
        assertEquals("sup1", captor.getValue().getConsignorId());
    }

    /** 非法/空的库存类型按 OWNED 处理：绝不能把受托货算进自有台账。 */
    @Test
    void ledger_blankOwnershipDefaultsToOwned() {
        when(warehouseMapper.selectList(any())).thenReturn(List.of(scrapWarehouse()));
        when(inventoryMapper.selectLedgerPage(any(), any())).thenReturn(new Page<>());

        service.pageLedger(1, 10, null, null, null, " BAD ", null);

        ArgumentCaptor<WmsInventoryLedgerQueryDTO> captor =
                ArgumentCaptor.forClass(WmsInventoryLedgerQueryDTO.class);
        verify(inventoryMapper).selectLedgerPage(any(), captor.capture());
        assertEquals(WmsConstants.OWNERSHIP_OWNED, captor.getValue().getOwnershipType());
    }
}
