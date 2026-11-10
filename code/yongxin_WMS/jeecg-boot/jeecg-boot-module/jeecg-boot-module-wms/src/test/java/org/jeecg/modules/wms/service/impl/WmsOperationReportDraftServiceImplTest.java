package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.service.IWmsSyncLogService;
import org.jeecg.modules.wms.sync.WmsKingdeeOperationReportReader;
import org.jeecg.modules.wms.vo.WmsOperationReportRowVO;
import org.jeecg.modules.wms.vo.WmsOperationReportSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工序汇报 → 半成品入库草稿（阶段 C）。
 */
class WmsOperationReportDraftServiceImplTest {

    private WmsKingdeeOperationReportReader reader;
    private WmsProductionInboundMapper inboundMapper;
    private WmsMaterialMapper materialMapper;
    private WmsWarehouseMapper warehouseMapper;
    private WmsOperationReportDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        reader = mock(WmsKingdeeOperationReportReader.class);
        inboundMapper = mock(WmsProductionInboundMapper.class);
        materialMapper = mock(WmsMaterialMapper.class);
        warehouseMapper = mock(WmsWarehouseMapper.class);
        service = new WmsOperationReportDraftServiceImpl(reader, inboundMapper,
                materialMapper, warehouseMapper, mock(IWmsSyncLogService.class));
    }

    private WmsOperationReportRowVO row(String stock) {
        WmsOperationReportRowVO r = new WmsOperationReportRowVO();
        r.setBillNo("GXHB659871");
        r.setMoNumber("MO1012616522");
        r.setMaterialCode("XA.00.06010101.04301704.B100");
        r.setMaterialName("焊接大改小铣刀");
        r.setLot("LOT-1");
        r.setQualifiedQty(new BigDecimal("100"));
        r.setSeq("0");
        r.setOperNumber("10");
        r.setStockNumber(stock);
        r.setStockName("W钻针半成品仓");
        r.setWorkshopName("三部段差");
        return r;
    }

    private void materialExists() {
        WmsMaterial m = new WmsMaterial();
        m.setId("mat1");
        m.setMaterialCode("XA.00.06010101.04301704.B100");
        when(materialMapper.selectOne(any())).thenReturn(m);
    }

    @Test
    void createsDraftInbound() {
        materialExists();
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(inboundMapper.selectOne(any())).thenReturn(null);
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row("W.02")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsProductionInbound> captor = ArgumentCaptor.forClass(WmsProductionInbound.class);
        verify(inboundMapper).insert(captor.capture());
        WmsProductionInbound created = captor.getValue();
        assertEquals(WmsConstants.PRODUCT_TYPE_SEMI, created.getProductType());
        assertEquals(WmsConstants.PROD_STATUS_DRAFT, created.getStatus());
        assertEquals("GXHB659871", created.getErpBillNo());
        assertEquals("GXHB659871#0#10", created.getErpEntryKey());
        assertEquals("MO1012616522", created.getWorkOrderNo());
        assertEquals("LOT-1", created.getBatchNo());
        assertEquals(0, new BigDecimal("100").compareTo(created.getGoodQty()));
        assertTrue(created.getManualRemark().contains("W.02"));
        // 阶段 C 不分配库位、不下发 WCS
        assertEquals(null, created.getLocationId());
        assertEquals(null, created.getWcsTaskNo());
    }

    @Test
    void skipsRowWithoutStock() {
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row(" ")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("未指定入库仓库"));
        verify(inboundMapper, never()).insert(any(WmsProductionInbound.class));
    }

    @Test
    void skipsExternalWarehouse() {
        materialExists();
        WmsWarehouse vmi = new WmsWarehouse();
        vmi.setWarehouseCode("CK007");
        vmi.setWarehouseType(WmsConstants.WAREHOUSE_TYPE_EXTERNAL);
        when(warehouseMapper.selectOne(any())).thenReturn(vmi);
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row("CK007")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("外部仓"));
        verify(inboundMapper, never()).insert(any(WmsProductionInbound.class));
    }

    @Test
    void skipsUnknownMaterial() {
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        when(materialMapper.selectOne(any())).thenReturn(null);
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row("W.02")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("不在 WMS 主数据"));
    }

    @Test
    void updatesExistingDraftInsteadOfDuplicating() {
        materialExists();
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        WmsProductionInbound existing = new WmsProductionInbound();
        existing.setId("pi1");
        existing.setStatus(WmsConstants.PROD_STATUS_DRAFT);
        when(inboundMapper.selectOne(any())).thenReturn(existing);
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row("W.02")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(1, vo.getUpdated());
        assertEquals(0, vo.getCreated());
        verify(inboundMapper, never()).insert(any(WmsProductionInbound.class));
        verify(inboundMapper).updateById(existing);
    }

    @Test
    void leavesAdvancedDraftUntouched() {
        materialExists();
        when(warehouseMapper.selectOne(any())).thenReturn(null);
        WmsProductionInbound existing = new WmsProductionInbound();
        existing.setId("pi1");
        existing.setStatus(WmsConstants.PROD_STATUS_INBOUND);
        when(inboundMapper.selectOne(any())).thenReturn(existing);
        when(reader.fetch(any(), any(), any(Integer.class))).thenReturn(List.of(row("W.02")));

        WmsOperationReportSyncResultVO vo = service.ingest(null, null, 100);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("已推进到"));
        verify(inboundMapper, never()).updateById(any(WmsProductionInbound.class));
    }
}
