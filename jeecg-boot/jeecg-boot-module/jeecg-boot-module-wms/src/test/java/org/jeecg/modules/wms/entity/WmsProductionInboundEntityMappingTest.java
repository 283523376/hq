package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @Description: 生产入库两张新表的表名与逻辑删除配置断言。
 *
 * <p>表名或逻辑删除配置写错，运行期每条 SQL 都会失败且很难从日志定位。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsProductionInboundEntityMappingTest {

    @Test
    void tableNames_mustMatchDdl() {
        assertEquals("wms_work_order", WmsWorkOrder.class.getAnnotation(TableName.class).value());
        assertEquals("wms_production_inbound", WmsProductionInbound.class.getAnnotation(TableName.class).value());
    }

    @Test
    void logicalDelete_onBothTables() throws Exception {
        for (Class<?> clazz : new Class<?>[] {WmsWorkOrder.class, WmsProductionInbound.class}) {
            Field f = clazz.getDeclaredField("deletedAt");
            TableLogic logic = f.getAnnotation(TableLogic.class);
            assertNotNull(logic, clazz.getSimpleName() + ".deletedAt");
            assertEquals("0", logic.value());
        }
    }
}
