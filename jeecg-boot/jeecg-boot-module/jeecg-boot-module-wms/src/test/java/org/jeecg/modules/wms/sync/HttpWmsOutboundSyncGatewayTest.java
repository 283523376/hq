package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsOutboundDetail;
import org.jeecg.modules.wms.entity.WmsOutboundOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 出库同步分流：领料→PRD_PickMtrl、销售→SAL_OUTSTOCK、销售订单→SAL_SaleOrder、未配置→mock。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class HttpWmsOutboundSyncGatewayTest {

    private WmsKingdeeProperties properties;
    private IWmsKingdeePickWriter pickWriter;
    private IWmsKingdeeSaleOrderWriter saleOrderWriter;
    private IWmsKingdeeOutstockWriter outstockWriter;
    private HttpWmsOutboundSyncGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        pickWriter = mock(IWmsKingdeePickWriter.class);
        saleOrderWriter = mock(IWmsKingdeeSaleOrderWriter.class);
        outstockWriter = mock(IWmsKingdeeOutstockWriter.class);
        gateway = new HttpWmsOutboundSyncGateway(properties, pickWriter, saleOrderWriter,
                outstockWriter, "");
        when(pickWriter.write(any(), any())).thenReturn(WmsOrderSyncResult.ok());
        when(saleOrderWriter.write(any(), any())).thenReturn(WmsOrderSyncResult.ok());
        when(outstockWriter.write(any(), any())).thenReturn(WmsOrderSyncResult.ok());
    }

    private WmsOutboundOrder order(String type) {
        WmsOutboundOrder order = new WmsOutboundOrder().setOutboundNo("O1").setOutboundType(type);
        order.setId("o1");
        return order;
    }

    private List<WmsOutboundDetail> details() {
        return List.of(new WmsOutboundDetail().setMaterialCode("M1"));
    }

    @Test
    void sync_pick_usesPickWriter() {
        assertTrue(gateway.sync(order("生产领料"), details()).isSuccess());
        verify(pickWriter).write(any(), any());
        verify(outstockWriter, never()).write(any(), any());
    }

    @Test
    void sync_sales_usesOutstockWriter() {
        assertTrue(gateway.sync(order("销售出库"), details()).isSuccess());
        verify(outstockWriter).write(any(), any());
        verify(pickWriter, never()).write(any(), any());
    }

    @Test
    void syncSaleOrder_usesSaleOrderWriter() {
        assertTrue(gateway.syncSaleOrder(order("销售出库"), details()).isSuccess());
        verify(saleOrderWriter).write(any(), any());
    }

    @Test
    void sync_nothingConfigured_returnsMock() {
        properties.setEnabled(false);

        assertTrue(gateway.sync(order("生产领料"), details()).isMocked());
        verify(pickWriter, never()).write(any(), any());
    }
}
