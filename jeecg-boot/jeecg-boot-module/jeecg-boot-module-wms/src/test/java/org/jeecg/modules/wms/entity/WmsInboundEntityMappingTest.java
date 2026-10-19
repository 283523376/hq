package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @Description: 入库域四张表的表名与逻辑删除配置断言。
 *
 * <p>表名拼错会让运行期每条 SQL 都 500；库存表有没有 deletedAt 决定删除守卫
 * 与 @TableLogic 的行为口径（spec 口径 8：库存不做逻辑删除）。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-19
 */
class WmsInboundEntityMappingTest {

    @Test
    void tableNames_mustMatchDdl() {
        assertEquals("wms_inbound_order", WmsInboundOrder.class.getAnnotation(TableName.class).value());
        assertEquals("wms_inbound_detail", WmsInboundDetail.class.getAnnotation(TableName.class).value());
        assertEquals("wms_inspection_task", WmsInspectionTask.class.getAnnotation(TableName.class).value());
        assertEquals("wms_inventory", WmsInventory.class.getAnnotation(TableName.class).value());
    }

    /** 单据/明细/检验三表走「删除时间戳」方案；库存表按口径 8 不做逻辑删除。 */
    @Test
    void logicalDelete_onlyOnDocumentTables() throws Exception {
        for (Class<?> clazz : new Class<?>[] {WmsInboundOrder.class, WmsInboundDetail.class, WmsInspectionTask.class}) {
            Field f = clazz.getDeclaredField("deletedAt");
            assertNotNull(f.getAnnotation(TableLogic.class), clazz.getSimpleName() + ".deletedAt");
        }
        assertThrows(NoSuchFieldException.class, () -> WmsInventory.class.getDeclaredField("deletedAt"),
                "wms_inventory 不应声明 deletedAt（口径 8：库存不做逻辑删除）");
    }
}
