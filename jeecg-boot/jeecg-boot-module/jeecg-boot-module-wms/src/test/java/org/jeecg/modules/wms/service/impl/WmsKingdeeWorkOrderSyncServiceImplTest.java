package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.dto.WmsWorkOrderPushDTO;
import org.jeecg.modules.wms.entity.WmsMaterial;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.service.IWmsWorkOrderService;
import org.jeecg.modules.wms.sync.WmsKingdeeFormClient;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.sync.WmsKingdeeResult;
import org.jeecg.modules.wms.vo.WmsKingdeeWorkOrderSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * @Description: 金蝶 PRD_MO → WMS 工单同步单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class WmsKingdeeWorkOrderSyncServiceImplTest {

    private WmsKingdeeFormClient formClient;
    private WmsKingdeeProperties properties;
    private IWmsWorkOrderService workOrderService;
    private WmsMaterialMapper materialMapper;
    private WmsKingdeeWorkOrderSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        formClient = mock(WmsKingdeeFormClient.class);
        properties = new WmsKingdeeProperties();
        properties.setMaterialMap(Map.of("PBC001", "WA.2206.2201.02000430.A003"));
        properties.setMaterialProductType(Map.of("PBC001", "半成品"));
        properties.setProductTypeMap(Map.of("1", "半成品"));
        properties.setStatusMap(Map.of("C", "生产中"));
        workOrderService = mock(IWmsWorkOrderService.class);
        materialMapper = mock(WmsMaterialMapper.class);
        service = new WmsKingdeeWorkOrderSyncServiceImpl(formClient, properties, workOrderService, materialMapper);
    }

    private WmsKingdeeResult rows(List<List<Object>> rows) {
        return WmsKingdeeResult.ok("[]", rows, null, null);
    }

    @Test
    void sync_singleMaterial_importsWorkOrder() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "0", new BigDecimal("2000")))));
        WmsMaterial material = new WmsMaterial();
        material.setMaterialCode("PBC001");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(workOrderService.getByWorkOrderNo("WORK00072")).thenReturn(null);

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", 50);

        assertEquals(1, vo.getQueried());
        assertEquals(1, vo.getImported());
        ArgumentCaptor<WmsWorkOrderPushDTO> captor = ArgumentCaptor.forClass(WmsWorkOrderPushDTO.class);
        verify(workOrderService).push(captor.capture());
        assertEquals("WORK00072", captor.getValue().getWorkOrderNo());
        assertEquals("PBC001", captor.getValue().getMaterialCode());
        assertEquals(0, captor.getValue().getPlanQty().compareTo(new BigDecimal("2000")));
        assertEquals("半成品", captor.getValue().getProductType());
        assertEquals("生产中", captor.getValue().getWorkOrderStatus());
    }

    @Test
    void sync_unknownMaterial_isSkipped() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.xxxx", "未知物料", "0", new BigDecimal("10")))));
        when(materialMapper.selectOne(any())).thenReturn(null);

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("WMS 物料不存在"));
        verify(workOrderService, never()).push(any());
    }

    @Test
    void sync_multipleMaterials_isSkipped() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(
                        List.of("WORK00072", "C", "1", "WA.1", "物料1", "0", new BigDecimal("1")),
                        List.of("WORK00072", "C", "1", "WA.2", "物料2", "0", new BigDecimal("1")))));

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", 50);

        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getMessages().get(0).contains("多个物料"));
    }

    @Test
    void sync_queryFailure_throws() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(WmsKingdeeResult.fail("鉴权失败"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.syncFromKingdee("WORK00072", 50));
        assertTrue(e.getMessage().contains("金蝶工单查询失败"));
    }

    @Test
    void sync_existingWorkOrder_countsUpdate() {
        when(formClient.executeBillQuery(eq("PRD_MO"), anyString(), anyString(), anyInt()))
                .thenReturn(rows(List.of(List.of("WORK00072", "C", "1",
                        "WA.2206.2201.02000430.A003", "半成品", "LOT1", new BigDecimal("5")))));
        WmsMaterial material = new WmsMaterial();
        material.setMaterialCode("PBC001");
        when(materialMapper.selectOne(any())).thenReturn(material);
        when(workOrderService.getByWorkOrderNo("WORK00072")).thenReturn(new WmsWorkOrder());

        WmsKingdeeWorkOrderSyncResultVO vo = service.syncFromKingdee("WORK00072", 50);

        assertEquals(1, vo.getUpdated());
        assertEquals(0, vo.getImported());
    }
}
