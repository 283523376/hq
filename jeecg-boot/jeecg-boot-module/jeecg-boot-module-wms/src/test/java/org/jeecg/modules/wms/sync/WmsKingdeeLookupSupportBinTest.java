package org.jeecg.modules.wms.sync;

import org.jeecg.modules.wms.entity.WmsKingdeeBin;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.mapper.WmsKingdeeBinMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 按物理货位取金蝶仓位内码（仓位属性模型，2026-10-28）。
 */
class WmsKingdeeLookupSupportBinTest {

    private WmsLocationMapper locationMapper;
    private WmsKingdeeBinMapper binMapper;
    private WmsKingdeeLookupSupport lookup;

    @BeforeEach
    void setUp() {
        locationMapper = mock(WmsLocationMapper.class);
        binMapper = mock(WmsKingdeeBinMapper.class);
        lookup = new WmsKingdeeLookupSupport(mock(WmsKingdeeFormClient.class),
                new WmsKingdeeProperties(), locationMapper, binMapper);
    }

    private WmsLocation location(String binId) {
        WmsLocation location = new WmsLocation();
        location.setId("loc1");
        location.setLocationCode("LB-R01-C01-L01");
        location.setKingdeeBinId(binId);
        return location;
    }

    private WmsKingdeeBin bin(String status, Long flexId) {
        WmsKingdeeBin bin = new WmsKingdeeBin();
        bin.setFlexNumber("001");
        bin.setBinNumber("01");
        bin.setSyncStatus(status);
        bin.setFlexId(flexId);
        return bin;
    }

    @Test
    void resolvesFlexIdFromLocationAttribute() {
        when(locationMapper.selectById("loc1")).thenReturn(location("b1"));
        when(binMapper.selectById("b1")).thenReturn(bin("正常", 100001L));
        assertEquals(100001L, lookup.stockLocIdByLocation("loc1"));
    }

    @Test
    void returnsNullWhenLocationHasNoAttribute() {
        when(locationMapper.selectById("loc1")).thenReturn(location(null));
        assertNull(lookup.stockLocIdByLocation("loc1"));
    }

    @Test
    void returnsNullWhenBinNotNormal() {
        when(locationMapper.selectById("loc1")).thenReturn(location("b1"));
        when(binMapper.selectById("b1")).thenReturn(bin("待处理", 100001L));
        assertNull(lookup.stockLocIdByLocation("loc1"));
    }

    @Test
    void returnsNullWhenBlankLocationId() {
        assertNull(lookup.stockLocIdByLocation(""));
        assertNull(lookup.stockLocIdByLocation(null));
    }
}
