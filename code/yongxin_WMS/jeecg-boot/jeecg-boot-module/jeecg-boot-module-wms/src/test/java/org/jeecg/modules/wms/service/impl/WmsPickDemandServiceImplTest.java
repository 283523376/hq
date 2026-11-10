package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundPickCreateDTO;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsWorkOrder;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsWorkOrderMapper;
import org.jeecg.modules.wms.service.IWmsOutboundService;
import org.jeecg.modules.wms.sync.IWmsKingdeePpbomReader;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.jeecg.modules.wms.vo.WmsOutboundRequirementVO;
import org.jeecg.modules.wms.vo.WmsPickDemandResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 领料待办自动生成单测：缺口计算（需求 - 在途申请）、单工单失败不阻断、开关/跳过。
 */
class WmsPickDemandServiceImplTest {

    private WmsKingdeeProperties properties;
    private IWmsKingdeePpbomReader ppbomReader;
    private WmsWorkOrderMapper workOrderMapper;
    private WmsOutboundOrderMapper outboundMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private IWmsOutboundService outboundService;
    private WmsPickDemandServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        ppbomReader = mock(IWmsKingdeePpbomReader.class);
        workOrderMapper = mock(WmsWorkOrderMapper.class);
        outboundMapper = mock(WmsOutboundOrderMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        outboundService = mock(IWmsOutboundService.class);
        service = new WmsPickDemandServiceImpl(properties, ppbomReader, workOrderMapper,
                outboundMapper, outboundDetailMapper, outboundService);
        when(outboundMapper.selectList(any())).thenReturn(List.of());
    }

    private WmsWorkOrder workOrder(String no) {
        WmsWorkOrder order = new WmsWorkOrder();
        order.setWorkOrderNo(no);
        order.setWorkOrderStatus(WmsConstants.WORK_ORDER_STATUS_IN_PROGRESS);
        return order;
    }

    private WmsOutboundRequirementVO requirement(String material, String qty) {
        WmsOutboundRequirementVO vo = new WmsOutboundRequirementVO();
        vo.setMaterialCode(material);
        vo.setRequiredQty(new BigDecimal(qty));
        vo.setUnit("zhi");
        vo.setMatched(true);
        return vo;
    }

    @Test
    @SuppressWarnings("unchecked")
    void generate_createsPickForFullGap() {
        when(workOrderMapper.selectList(any())).thenReturn(List.of(workOrder("MO1")));
        when(ppbomReader.read("MO1")).thenReturn(List.of(requirement("M1", "10")));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("LL1");
        when(outboundService.createPick(any())).thenReturn(created);

        WmsPickDemandResultVO vo = service.generatePendingPicks();

        assertEquals(1, vo.getCreated());
        assertEquals(List.of("LL1"), vo.getCreatedOrders());
        ArgumentCaptor<WmsOutboundPickCreateDTO> captor = ArgumentCaptor.forClass(WmsOutboundPickCreateDTO.class);
        verify(outboundService).createPick(captor.capture());
        WmsOutboundPickCreateDTO dto = captor.getValue();
        assertEquals("MO1", dto.getWorkOrderNo());
        // 需求来源标记为金蝶用料清单
        assertEquals(WmsConstants.REQUIREMENT_SOURCE_PPBOM, dto.getRequirementSource());
        assertEquals(1, dto.getDetails().size());
        assertEquals(0, new BigDecimal("10").compareTo(dto.getDetails().get(0).getRequiredQty()));
    }

    @Test
    void generate_subtractsInFlightQty() {
        when(workOrderMapper.selectList(any())).thenReturn(List.of(workOrder("MO1")));
        when(ppbomReader.read("MO1")).thenReturn(List.of(requirement("M1", "10")));
        WmsOutboundOrder open = new WmsOutboundOrder();
        open.setId("o1");
        open.setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_ALLOCATE);
        when(outboundMapper.selectList(any())).thenReturn(List.of(open));
        WmsOutboundDetail line = new WmsOutboundDetail();
        line.setMaterialCode("M1");
        line.setRequiredQty(new BigDecimal("6"));
        when(outboundDetailMapper.selectList(any())).thenReturn(List.of(line));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("LL2");
        when(outboundService.createPick(any())).thenReturn(created);

        WmsPickDemandResultVO vo = service.generatePendingPicks();

        assertEquals(1, vo.getCreated());
        ArgumentCaptor<WmsOutboundPickCreateDTO> captor = ArgumentCaptor.forClass(WmsOutboundPickCreateDTO.class);
        verify(outboundService).createPick(captor.capture());
        // 10 - 6 = 4
        assertEquals(0, new BigDecimal("4").compareTo(captor.getValue().getDetails().get(0).getRequiredQty()));
    }

    @Test
    void generate_skipsWhenNoGap() {
        when(workOrderMapper.selectList(any())).thenReturn(List.of(workOrder("MO1")));
        when(ppbomReader.read("MO1")).thenReturn(List.of(requirement("M1", "5")));
        WmsOutboundOrder open = new WmsOutboundOrder();
        open.setId("o1");
        when(outboundMapper.selectList(any())).thenReturn(List.of(open));
        WmsOutboundDetail line = new WmsOutboundDetail();
        line.setMaterialCode("M1");
        line.setRequiredQty(new BigDecimal("5"));
        when(outboundDetailMapper.selectList(any())).thenReturn(List.of(line));

        WmsPickDemandResultVO vo = service.generatePendingPicks();

        assertEquals(0, vo.getCreated());
        assertEquals(1, vo.getSkipped());
        verify(outboundService, never()).createPick(any());
    }

    @Test
    void generate_singleWorkOrderFailureDoesNotBlockOthers() {
        when(workOrderMapper.selectList(any())).thenReturn(List.of(workOrder("MO1"), workOrder("MO2")));
        when(ppbomReader.read("MO1")).thenThrow(new RuntimeException("PPBOM 读取失败"));
        when(ppbomReader.read("MO2")).thenReturn(List.of(requirement("M2", "3")));
        WmsOutboundOrder created = new WmsOutboundOrder();
        created.setOutboundNo("LL3");
        when(outboundService.createPick(any())).thenReturn(created);

        WmsPickDemandResultVO vo = service.generatePendingPicks();

        assertEquals(1, vo.getCreated());
        assertEquals(1, vo.getSkipped());
        assertTrue(vo.getSkippedReasons().get(0).contains("MO1"));
    }

    @Test
    void generate_skipsWhenKingdeeDisabled() {
        properties.setEnabled(false);
        WmsPickDemandResultVO vo = service.generatePendingPicks();
        assertTrue(vo.getMessage().contains("跳过"));
        verify(workOrderMapper, never()).selectList(any());
    }
}
