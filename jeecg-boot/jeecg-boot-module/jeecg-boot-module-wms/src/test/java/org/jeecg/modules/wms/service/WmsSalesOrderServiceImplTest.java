package org.jeecg.modules.wms.service;

import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrder;
import org.jeecg.modules.wms.entity.WmsSalesOrderDetail;
import org.jeecg.modules.wms.mapper.WmsMaterialMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsOutboundOrderMapper;
import org.jeecg.modules.wms.mapper.WmsSalesOrderDetailMapper;
import org.jeecg.modules.wms.mapper.WmsSalesOrderMapper;
import org.jeecg.modules.wms.service.impl.WmsSalesOrderServiceImpl;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.jeecg.modules.wms.sync.IWmsKingdeeSaleOrderReader;
import org.jeecg.modules.wms.sync.WmsKingdeeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 销售订单快照：已出库数量汇总（剩余可出 = 订单量 - 已出库）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-22
 */
class WmsSalesOrderServiceImplTest {

    private WmsSalesOrderMapper orderMapper;
    private WmsSalesOrderDetailMapper detailMapper;
    private WmsOutboundOrderMapper outboundOrderMapper;
    private WmsOutboundDetailMapper outboundDetailMapper;
    private WmsSalesOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(WmsSalesOrderMapper.class);
        detailMapper = mock(WmsSalesOrderDetailMapper.class);
        outboundOrderMapper = mock(WmsOutboundOrderMapper.class);
        outboundDetailMapper = mock(WmsOutboundDetailMapper.class);
        service = new WmsSalesOrderServiceImpl(detailMapper, mock(WmsMaterialMapper.class),
                outboundOrderMapper, outboundDetailMapper,
                mock(IWmsKingdeeSaleOrderReader.class), new WmsKingdeeProperties(), mock(ISysBaseAPI.class));
        WmsTestSupport.inject(service, "baseMapper", orderMapper);
    }

    @Test
    void refreshShippedQty_sumsConfirmedOutboundAndUpdatesDetail() {
        WmsSalesOrder snapshot = new WmsSalesOrder();
        snapshot.setId("so1");
        snapshot.setSalesOrderNo("SO1");
        when(orderMapper.selectList(any())).thenReturn(List.of(snapshot));

        WmsSalesOrderDetail line = new WmsSalesOrderDetail()
                .setSalesOrderId("so1")
                .setMaterialCode("PBC001")
                .setOrderQty(new BigDecimal("10"));
        when(detailMapper.selectList(any())).thenReturn(List.of(line));

        WmsOutboundOrder outbound = new WmsOutboundOrder();
        outbound.setId("o1");
        when(outboundOrderMapper.selectList(any())).thenReturn(List.of(outbound));
        when(outboundDetailMapper.selectList(any())).thenReturn(List.of(
                new WmsOutboundDetail().setMaterialCode("PBC001").setActualQty(new BigDecimal("3")),
                new WmsOutboundDetail().setMaterialCode("PBC001").setActualQty(new BigDecimal("2")),
                new WmsOutboundDetail().setMaterialCode("OTHER").setActualQty(new BigDecimal("9"))));

        service.refreshShippedQty("SO1");

        ArgumentCaptor<WmsSalesOrderDetail> captor = ArgumentCaptor.forClass(WmsSalesOrderDetail.class);
        verify(detailMapper).updateById(captor.capture());
        assertEquals(new BigDecimal("5"), captor.getValue().getShippedQty());
    }

    @Test
    void refreshShippedQty_unknownOrder_isNoop() {
        when(orderMapper.selectList(any())).thenReturn(List.of());
        service.refreshShippedQty("NOPE");
        verify(detailMapper, org.mockito.Mockito.never()).updateById(any(WmsSalesOrderDetail.class));
    }

    @Test
    void refreshShippedQty_blankNo_isNoop() {
        service.refreshShippedQty("");
        verify(orderMapper, org.mockito.Mockito.never()).selectList(any());
    }

    @Test
    void requireApprovedDetails_blankNo_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.preview(""));
    }
}
