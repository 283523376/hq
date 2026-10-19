package org.jeecg.modules.wms.util;

import org.jeecg.modules.wms.constant.WmsConstants;

/**
 * 入库路由规则（分类类型 → 库区类型）。唯一实现，服务与控制器共用，避免两处漂移。
 *
 * <p>口径 10：取物料挂靠分类的 category_type，不做向上继承；为空/其他 → null（不自动分配）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 * @deprecated 方案 A（原材料+焊接线边仓）已移出项目范围；辅料→线边柜分支仅供历史
 *             RK-001 数据回滚使用，新流程不得再依赖。
 */
@Deprecated
public final class WmsInboundRouting {

    private WmsInboundRouting() {
    }

    /**
     * @param categoryType 物料分类类型（半成品/辅料/其他）
     * @return 目标库区类型（立库/线边柜）；无法路由时返回 null
     */
    public static String toWarehouseType(String categoryType) {
        if (WmsConstants.CATEGORY_TYPE_HALF_FINISHED.equals(categoryType)) {
            return WmsConstants.WAREHOUSE_TYPE_STEREO;
        }
        if (WmsConstants.CATEGORY_TYPE_AUXILIARY.equals(categoryType)) {
            return WmsConstants.WAREHOUSE_TYPE_LINE_SIDE;
        }
        return null;
    }
}
