package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库同步报文：不良原因与废品仓库位必须随报文送出。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-20
 */
class HttpWmsProductionInboundSyncGatewayPayloadTest {

    @Test
    void payload_includesDefectReasonAndScrapLocation() {
        HttpWmsProductionInboundSyncGateway gateway = new HttpWmsProductionInboundSyncGateway(
                "", "", "", "", "", "", "");
        WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
        WmsLocation scrap = new WmsLocation();
        scrap.setId("scrap1");
        scrap.setLocationCode("FPC-DEFAULT");
        when(locationMapper.selectById("scrap1")).thenReturn(scrap);
        WmsTestSupport.inject(gateway, "locationMapper", locationMapper);
        WmsTestSupport.inject(gateway, "warehouseMapper", mock(WmsWarehouseMapper.class));

        WmsProductionInbound record = new WmsProductionInbound();
        record.setInboundNo("SC1");
        record.setWorkOrderNo("WO1");
        record.setProductType("成品");
        record.setMaterialCode("FG001");
        record.setBatchNo("B1");
        record.setQuantity(new BigDecimal("2"));
        record.setGoodQty(BigDecimal.ZERO);
        record.setBadQty(new BigDecimal("2"));
        record.setScrapLocationId("scrap1");
        record.setDefectRemark("外观不良");

        Map<String, Object> payload = gateway.buildPayload(record);

        assertEquals("FPC-DEFAULT", payload.get("scrapLocationCode"));
        assertEquals("外观不良", payload.get("defectRemark"));
        verify(locationMapper).selectById("scrap1");
    }
}
