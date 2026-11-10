package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Description: 盘点冻结守卫（spec §5.3）：冻结库位必须拦下，未冻结/不存在放行
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsStocktakeFreezeGuardTest {

    @Test
    void frozenLocationShouldThrowWithCode() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> WmsStocktakeFreezeGuard.assertNotFrozen(location("A-01-01", WmsConstants.STOCKTAKE_FROZEN)));
        assertTrue(e.getMessage().contains("A-01-01"), e.getMessage());
        assertTrue(e.getMessage().contains("盘点冻结"), e.getMessage());
    }

    @Test
    void unfrozenOrMissingLocationShouldPass() {
        assertDoesNotThrow(() -> WmsStocktakeFreezeGuard.assertNotFrozen(
                location("A-01-02", WmsConstants.STOCKTAKE_UNFROZEN)));
        assertDoesNotThrow(() -> WmsStocktakeFreezeGuard.assertNotFrozen((WmsLocation) null));
    }

    @Test
    void idOverloadShouldConsultMapper() {
        WmsLocationMapper mapper = mock(WmsLocationMapper.class);
        when(mapper.selectById("loc-1")).thenReturn(location("A-01-03", WmsConstants.STOCKTAKE_FROZEN));

        assertThrows(RuntimeException.class, () -> WmsStocktakeFreezeGuard.assertNotFrozen("loc-1", mapper));
    }

    private static WmsLocation location(String code, String frozen) {
        WmsLocation location = new WmsLocation();
        location.setLocationCode(code);
        location.setStocktakeFrozen(frozen);
        return location;
    }
}
