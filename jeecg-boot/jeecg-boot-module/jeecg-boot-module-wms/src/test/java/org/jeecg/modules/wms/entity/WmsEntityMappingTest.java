package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @Description: 表名与列映射的配置断言。
 *
 * <p>这些类没有行为，但它们上面一个拼错的 {@code @TableName} 会让运行期每条 SQL 都 500。
 * 本测试把该风险的发现时间从「接口报错」提前到「编译后 50 毫秒」。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-10-15
 */
class WmsEntityMappingTest {

    @Test
    void tableNames_mustMatchDdl() {
        assertEquals("wms_warehouse", WmsWarehouse.class.getAnnotation(TableName.class).value());
        assertEquals("wms_location", WmsLocation.class.getAnnotation(TableName.class).value());
        assertEquals("wms_device", WmsDevice.class.getAnnotation(TableName.class).value());
    }

    /** 展示用字段必须标 exist=false，否则 QueryGenerator 会拼出不存在的列导致 SQL 报错。 */
    @Test
    void nonPersistentFields_mustBeMarkedExistFalse() throws Exception {
        assertTrue(isNonPersistent(WmsLocation.class, "warehouseName"), "WmsLocation.warehouseName");
        assertTrue(isNonPersistent(WmsDevice.class, "warehouseName"), "WmsDevice.warehouseName");
        assertTrue(isNonPersistent(WmsWarehouse.class, "outOfEnvelopeCount"), "WmsWarehouse.outOfEnvelopeCount");
    }

    @Test
    void mappers_mustBePresent() {
        assertNotNull(WmsWarehouseMapper.class);
        assertNotNull(WmsLocationMapper.class);
        assertNotNull(WmsDeviceMapper.class);
    }

    private static boolean isNonPersistent(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        TableField tf = field.getAnnotation(TableField.class);
        return tf != null && !tf.exist();
    }
}