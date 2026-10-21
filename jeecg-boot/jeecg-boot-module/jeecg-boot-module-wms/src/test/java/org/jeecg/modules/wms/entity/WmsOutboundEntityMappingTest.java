package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jeecg.modules.wms.constant.WmsConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @Description: 出库三张表的表名、逻辑删除配置与库存新增列断言。
 *
 * <p>表名或逻辑删除配置写错，运行期每条 SQL 都会失败且很难从日志定位。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-21
 */
class WmsOutboundEntityMappingTest {

    @Test
    void tableNames_mustMatchDdl() {
        assertEquals("wms_outbound_order", WmsOutboundOrder.class.getAnnotation(TableName.class).value());
        assertEquals("wms_outbound_detail", WmsOutboundDetail.class.getAnnotation(TableName.class).value());
        assertEquals("wms_outbound_allocation", WmsOutboundAllocation.class.getAnnotation(TableName.class).value());
    }

    @Test
    void logicalDelete_onThreeTables() throws Exception {
        for (Class<?> clazz : new Class<?>[] {WmsOutboundOrder.class, WmsOutboundDetail.class, WmsOutboundAllocation.class}) {
            Field field = clazz.getDeclaredField("deletedAt");
            TableLogic logic = field.getAnnotation(TableLogic.class);
            assertNotNull(logic, clazz.getSimpleName() + ".deletedAt");
            assertEquals("0", logic.value());
        }
    }

    @Test
    void inventory_hasContainerAndLockColumns() throws Exception {
        assertNotNull(WmsInventory.class.getDeclaredField("containerCode"));
        assertNotNull(WmsInventory.class.getDeclaredField("lockedQty"));
    }

    @Test
    void constants_haveExactValues() {
        assertEquals("生产领料", WmsConstants.OUTBOUND_TYPE_PICK);
        assertEquals("销售出库", WmsConstants.OUTBOUND_TYPE_SALES);
        assertEquals("待复核", WmsConstants.OUTBOUND_STATUS_PENDING_REVIEW);
        assertEquals("1920000000000000300", WmsConstants.OUTBOUND_SYNC_JOB_ID);
    }
}
