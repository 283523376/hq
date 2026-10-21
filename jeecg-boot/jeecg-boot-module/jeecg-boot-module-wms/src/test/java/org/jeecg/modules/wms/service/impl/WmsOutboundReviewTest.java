package org.jeecg.modules.wms.service.impl;

import org.jeecg.common.api.dto.message.MessageDTO;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.dto.WmsOutboundReviewDTO;
import org.jeecg.modules.wms.entity.WmsOutboundAllocation;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.mapper.WmsInventoryMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundAllocationMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: CK-002 复核：不一致告警、改数量/重扫恢复、放行原因必填。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundReviewTest {

    private WmsOutboundOrderMapper orderMapper;
    private WmsOutboundAllocationMapper allocationMapper;
    private WmsOutboundDetailMapper detailMapper;
    private ISysBaseAPI sysBaseAPI;
    private WmsOutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsOutboundOrderMapper.class);
        allocationMapper = mock(WmsOutboundAllocationMapper.class);
        detailMapper = mock(WmsOutboundDetailMapper.class);
        sysBaseAPI = mock(ISysBaseAPI.class);
        service = new WmsOutboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
        WmsTestSupport.inject(service, "allocationMapper", allocationMapper);
        WmsTestSupport.inject(service, "detailMapper", detailMapper);
        WmsTestSupport.inject(service, "inventoryMapper", mock(WmsInventoryMapper.class));
        WmsTestSupport.inject(service, "locationMapper", mock(WmsLocationMapper.class));
        WmsTestSupport.inject(service, "sysBaseAPI", sysBaseAPI);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(allocationMapper.update(any(), any())).thenReturn(1);

        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("XS1")
                .setOutboundType(WmsConstants.OUTBOUND_TYPE_SALES)
                .setStatus(WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        order.setId("o1");
        when(orderMapper.selectById("o1")).thenReturn(order);

        WmsOutboundAllocation allocation = new WmsOutboundAllocation()
                .setOutboundId("o1").setDetailId("d1").setInventoryId("i1")
                .setContainerCode("CT1").setAllocatedQty(new BigDecimal("10"))
                .setActualQty(BigDecimal.ZERO)
                .setReviewStatus(WmsConstants.REVIEW_STATUS_PENDING);
        allocation.setId("a1");
        when(allocationMapper.selectList(any())).thenReturn(List.of(allocation));

        WmsOutboundDetail detail = new WmsOutboundDetail().setMaterialCode("PBC001")
                .setRequiredQty(new BigDecimal("10")).setAllocatedQty(new BigDecimal("10"));
        detail.setId("d1");
        when(detailMapper.selectList(any())).thenReturn(List.of(detail));
    }

    private WmsOutboundReviewDTO dto(String scanCode, String actualQty) {
        WmsOutboundReviewDTO dto = new WmsOutboundReviewDTO();
        WmsOutboundReviewDTO.Item item = new WmsOutboundReviewDTO.Item();
        item.setAllocationId("a1");
        item.setScanCode(scanCode);
        item.setActualQty(new BigDecimal(actualQty));
        dto.setDetails(List.of(item));
        return dto;
    }

    @Test
    void review_mismatch_setsReviewExceptionAndAlerts() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.review("o1", dto("WRONG", "10")));

        assertTrue(e.getMessage().contains("复核不一致"));
        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_REVIEW_EXCEPTION, captor.getValue().getStatus());
        ArgumentCaptor<MessageDTO> message = ArgumentCaptor.forClass(MessageDTO.class);
        verify(sysBaseAPI).sendSysAnnouncement(message.capture());
        assertEquals(WmsConstants.OUTBOUND_REVIEW_ALERT_TITLE, message.getValue().getTitle());
    }

    @Test
    void review_rescanWithCorrectCode_recovers() {
        service.review("o1", dto("PBC001", "10"));

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }

    @Test
    void review_containerCodeIsAccepted() {
        service.review("o1", dto("CT1", "10"));

        ArgumentCaptor<WmsOutboundOrder> captor = ArgumentCaptor.forClass(WmsOutboundOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW, captor.getValue().getStatus());
    }

    @Test
    void review_actualQtyOverAllocated_throws() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.review("o1", dto("PBC001", "11")));
        assertEquals("实收数量不能大于分配数量", e.getMessage());
    }

    @Test
    void review_forceWithoutReason_throws() {
        WmsOutboundReviewDTO dto = dto("WRONG", "10");
        dto.setReleaseMismatch(true);
        dto.setReleaseReason("");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.review("o1", dto));
        assertEquals("放行原因不能为空", e.getMessage());
    }
}
