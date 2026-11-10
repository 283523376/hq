package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 启用金蝶时同步网关走回写器，不再走通用 ERP URL。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class HttpWmsProductionInboundSyncGatewayKingdeeTest {

    private WmsKingdeeProperties properties;
    private IWmsKingdeeInboundWriter writer;
    private HttpWmsProductionInboundSyncGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new WmsKingdeeProperties();
        properties.setEnabled(true);
        writer = mock(IWmsKingdeeInboundWriter.class);
        gateway = new HttpWmsProductionInboundSyncGateway("", "", "", "", "", "", "");
        WmsTestSupport.inject(gateway, "kingdeeProperties", properties);
        WmsTestSupport.inject(gateway, "kingdeeInboundWriter", writer);
    }

    private WmsProductionInbound record() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setInboundNo("SC1");
        record.setWorkOrderNo("WO1");
        record.setProductType("成品");
        record.setMaterialCode("FG001");
        record.setBatchNo("B1");
        record.setQuantity(BigDecimal.ONE);
        record.setGoodQty(BigDecimal.ONE);
        record.setBadQty(BigDecimal.ZERO);
        return record;
    }

    @Test
    void sync_kingdeeEnabled_usesWriter() {
        when(writer.write(any())).thenReturn(WmsOrderSyncResult.ok());

        assertTrue(gateway.sync(record()).isSuccess());
        verify(writer).write(any());
    }

    @Test
    void sync_kingdeeFailure_returnsFailure() {
        when(writer.write(any())).thenReturn(WmsOrderSyncResult.fail("金蝶保存失败"));

        WmsOrderSyncResult result = gateway.sync(record());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("ERP(金蝶)"));
        assertTrue(result.getErrorMessage().contains("金蝶保存失败"));
    }
}
