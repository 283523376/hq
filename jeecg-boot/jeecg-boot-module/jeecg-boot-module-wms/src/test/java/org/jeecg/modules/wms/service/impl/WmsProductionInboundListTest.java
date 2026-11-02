package org.jeecg.modules.wms.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsProductionInbound;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsProductionInboundMapper;
import org.jeecg.modules.wms.support.WmsTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Description: 生产入库列表筛选与详情单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionInboundListTest {

    private WmsProductionInboundMapper inboundMapper;
    private WmsLocationMapper locationMapper;
    private WmsProductionInboundServiceImpl service;

    @BeforeEach
    void setUp() {
        inboundMapper = mock(WmsProductionInboundMapper.class);
        locationMapper = mock(WmsLocationMapper.class);
        service = new WmsProductionInboundServiceImpl();
        WmsTestSupport.inject(service, "baseMapper", inboundMapper);
        WmsTestSupport.inject(service, "inboundMapper", inboundMapper);
        WmsTestSupport.inject(service, "locationMapper", locationMapper);
    }

    @Test
    void list_filtersByStatusAndCarrier() {
        when(inboundMapper.selectPage(any(), any())).thenReturn(new Page<>());
        WmsProductionInbound query = new WmsProductionInbound();
        query.setStatus("已入库");
        query.setInboundNo("SC001");
        query.setCarrierCode("RFID-1");

        service.listPage(1, 10, query, null);

        verify(inboundMapper).selectPage(any(), ArgumentMatchers.<Wrapper<WmsProductionInbound>>argThat(w -> {
            String sql = ((QueryWrapper<?>) w).getSqlSegment();
            return sql.contains("status") && sql.contains("carrier_code");
        }));
    }

    @Test
    void list_enrichesLocationCode() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        record.setLocationId("loc1");
        Page<WmsProductionInbound> page = new Page<>();
        page.setRecords(java.util.List.of(record));
        when(inboundMapper.selectPage(any(), any())).thenReturn(page);
        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("LB-R01-C01-L01");
        when(locationMapper.selectBatchIds(any())).thenReturn(java.util.List.of(location));

        com.baomidou.mybatisplus.core.metadata.IPage<WmsProductionInbound> result =
                service.listPage(1, 10, new WmsProductionInbound(), null);

        assertEquals("LB-R01-C01-L01", result.getRecords().get(0).getLocationCode());
    }

    @Test
    void detail_missing_throws() {
        when(inboundMapper.selectById("none")).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.detail("none"));
        assertEquals("生产入库记录不存在", e.getMessage());
    }

    @Test
    void detail_returnsRecord() {
        WmsProductionInbound record = new WmsProductionInbound();
        record.setId("id1");
        when(inboundMapper.selectById("id1")).thenReturn(record);
        assertEquals("id1", service.detail("id1").getId());
    }
}
