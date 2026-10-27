package org.jeecg.modules.wms.service.impl;

import org.jeecg.modules.wms.constant.WmsConstants;
import org.jeecg.modules.wms.entity.WmsLocation;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;

/**
 * @Description: 盘点冻结守卫——三条库存写入路径（出库分配 / 同库区移库 / 入库上架）的统一闸门。
 *
 * <p>做成静态工具而不是 Spring Bean：三个调用方本来就持有 {@code WmsLocationMapper}，
 * 走静态判定避免为每处再加一个构造参数（那会连带改一堆既有测试的构造调用），
 * 判定逻辑本身也不依赖任何可变状态。
 *
 * @Author: 永鑫WMS
 * @Date 2026-10-26
 */
public final class WmsStocktakeFreezeGuard {

    private WmsStocktakeFreezeGuard() {
    }

    /** 已冻结则抛异常；库位不存在或未冻结直接放行。 */
    public static void assertNotFrozen(WmsLocation location) {
        if (location != null && WmsConstants.STOCKTAKE_FROZEN.equals(location.getStocktakeFrozen())) {
            throw new RuntimeException("库位 " + location.getLocationCode() + " 处于盘点冻结，暂不可操作");
        }
    }

    /** 便捷重载：按 id 取库位后判定。 */
    public static void assertNotFrozen(String locationId, WmsLocationMapper locationMapper) {
        if (locationId == null || locationId.isEmpty() || locationMapper == null) {
            return;
        }
        assertNotFrozen(locationMapper.selectById(locationId));
    }
}
