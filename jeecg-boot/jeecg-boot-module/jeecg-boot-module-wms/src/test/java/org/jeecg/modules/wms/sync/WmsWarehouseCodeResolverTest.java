package org.jeecg.modules.wms.sync;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.jeecg.modules.wms.entity.WmsInboundDetail;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.entity.WmsWarehouse;
import org.jeecg.modules.wms.mapper.WmsInboundDetailMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 货位 → 金蝶仓库编码解析（FStockId 的数据来源）单测。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsWarehouseCodeResolverTest {

    private final WmsLocationMapper locationMapper = mock(WmsLocationMapper.class);
    private final WmsWarehouseMapper warehouseMapper = mock(WmsWarehouseMapper.class);
    private final WmsInboundDetailMapper detailMapper = mock(WmsInboundDetailMapper.class);
    private final WmsWarehouseCodeResolver resolver =
            new WmsWarehouseCodeResolver(locationMapper, warehouseMapper, detailMapper);

    /** 正常链路：货位 → 库区 → warehouse_code（与金蝶 1:1，直接返回）。 */
    @Test
    void byLocation_shouldResolveWarehouseCode() {
        WmsLocation location = new WmsLocation().setWarehouseId("WH1");
        location.setId("L1");
        when(locationMapper.selectById("L1")).thenReturn(location);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseCode(" D.02 ");
        warehouse.setId("WH1");
        when(warehouseMapper.selectById("WH1")).thenReturn(warehouse);

        assertEquals("D.02", resolver.byLocation("L1"), "仓库编码需 trim 后返回");
    }

    /** 任何一环缺失都返回 null（调用方据此显式失败，不省略金蝶必填字段）。 */
    @Test
    void byLocation_shouldReturnNullWhenAnyLinkMissing() {
        assertNull(resolver.byLocation(null));
        assertNull(resolver.byLocation(""));
        assertNull(resolver.byLocation("L-missing"), "库位不存在");

        WmsLocation noWarehouse = new WmsLocation().setWarehouseId("");
        noWarehouse.setId("L2");
        when(locationMapper.selectById("L2")).thenReturn(noWarehouse);
        assertNull(resolver.byLocation("L2"), "库位没有库区");

        WmsLocation location = new WmsLocation().setWarehouseId("WH-missing");
        location.setId("L3");
        when(locationMapper.selectById("L3")).thenReturn(location);
        assertNull(resolver.byLocation("L3"), "库区不存在");
    }

    /** 入库单：按明细货位解析，取第一条能解析到的仓库。 */
    @Test
    void byInboundOrder_shouldResolveFromFirstDetailWithLocation() {
        WmsInboundDetail noLocation = new WmsInboundDetail();
        noLocation.setId("D1");
        WmsInboundDetail withLocation = new WmsInboundDetail().setLocationId("L1");
        withLocation.setId("D2");
        when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(noLocation, withLocation));
        WmsLocation location = new WmsLocation().setWarehouseId("WH1");
        location.setId("L1");
        when(locationMapper.selectById("L1")).thenReturn(location);
        WmsWarehouse warehouse = new WmsWarehouse().setWarehouseCode("D.02");
        warehouse.setId("WH1");
        when(warehouseMapper.selectById("WH1")).thenReturn(warehouse);

        assertEquals("D.02", resolver.byInboundOrder("IN1"));
    }

    /** 明细全无货位 / 无明细 → null。 */
    @Test
    void byInboundOrder_shouldReturnNullWhenNoDetailHasLocation() {
        assertNull(resolver.byInboundOrder(null));
        assertNull(resolver.byInboundOrder(""));
        when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(new WmsInboundDetail()));
        assertNull(resolver.byInboundOrder("IN1"));
    }
}
