package org.jeecg.modules.wms.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * @Description: 主数据骨架常量单测——分类类型、仓库类型、工单类型四枚举（spec 2026-10-26 §9）
 * @Author: 永鑫WMS
 * @Date: 2026-10-26
 */
class WmsConstantsMasterDataTest {

    @Test
    void shouldExposeFourCategoryTypes() {
        assertEquals("半成品", WmsConstants.CATEGORY_TYPE_HALF_FINISHED);
        assertEquals("初级产成品", WmsConstants.CATEGORY_TYPE_PRIMARY);
        assertEquals("涂层产成品", WmsConstants.CATEGORY_TYPE_COATED);
        assertEquals("产成品", WmsConstants.CATEGORY_TYPE_FINISHED);
    }

    @Test
    void shouldExposeNormalWarehouseType() {
        assertEquals("普通仓", WmsConstants.WAREHOUSE_TYPE_NORMAL);
    }

    @Test
    void shouldExposeFourWorkOrderTypes() {
        assertEquals("客户订单", WmsConstants.WORK_ORDER_TYPE_CUSTOMER);
        assertEquals("备库订单", WmsConstants.WORK_ORDER_TYPE_STOCK);
        assertEquals("委外订单", WmsConstants.WORK_ORDER_TYPE_OUTSOURCE);
        assertEquals("受托订单", WmsConstants.WORK_ORDER_TYPE_TRUSTED);
    }

    /**
     * 工单类型常量与 wms_order 域既有的 ORDER_TYPE_OUTSOURCE 同值不同名——
     * 两者语义不同（订单类型 vs 工单类型），不得互相替代。
     */
    @Test
    void workOrderTypeOutsourceShouldNotReplaceOrderTypeOutsource() {
        assertNotEquals("ORDER_TYPE_OUTSOURCE", "WORK_ORDER_TYPE_OUTSOURCE");
        assertEquals("委外订单", WmsConstants.ORDER_TYPE_OUTSOURCE);
    }
}
