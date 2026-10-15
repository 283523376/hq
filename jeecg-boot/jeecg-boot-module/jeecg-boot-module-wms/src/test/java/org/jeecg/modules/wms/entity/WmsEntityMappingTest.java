package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import org.jeecg.modules.wms.mapper.WmsDeviceMapper;
import org.jeecg.modules.wms.mapper.WmsLocationMapper;
import org.jeecg.modules.wms.mapper.WmsWarehouseMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * 这三张配置表**不得**使用逻辑删除。
     *
     * <p>端到端验收抓到的缺陷：{@code uk_wms_*_code} 与 {@code uk_wms_location_grid}
     * 都只建在业务列上、不含 {@code del_flag}，而实体的 {@code @TableLogic} 会让
     * <ul>
     *   <li>{@code checkCodeExists} 自动加 {@code del_flag=0} → 看不见被逻辑删除的行 → 谎报「编码可用」，</li>
     *   <li>随后的 INSERT 撞唯一索引 → 用户看到的是原始 SQL 异常而不是「编码已存在」，</li>
     *   <li>更糟：库位被逻辑删除后重跑生成，那几行会被 {@code INSERT IGNORE} <b>静默跳过</b>
     *       却不会被恢复（列表里也看不见），直接破坏「重跑同范围可安全续做」这条承诺。</li>
     * </ul>
     *
     * <p>唯一索引与逻辑删除在语义上不可兼得，除非把索引也带上删标记（那样同一编码只能删一次）。
     * 这三张是**配置表**：删除动作本身已由守卫保护（有库位/有设备不许删库区、占用或故障不许删库位），
     * 留痕由 {@code @AutoLog} 的操作日志承担，故选择**物理删除**，
     * 让两个唯一索引名副其实 —— 编码与网格位只被「现存记录」占用。
     *
     * <p>若哪天有人为了「统一风格」把 {@code @TableLogic} 加回来，本用例会红。
     */
    @Test
    void configTables_mustNotUseLogicalDelete() {
        assertFalse(hasTableLogic(WmsWarehouse.class), "WmsWarehouse.delFlag 不应带 @TableLogic");
        assertFalse(hasTableLogic(WmsLocation.class), "WmsLocation.delFlag 不应带 @TableLogic");
        assertFalse(hasTableLogic(WmsDevice.class), "WmsDevice.delFlag 不应带 @TableLogic");
    }

    private static boolean hasTableLogic(Class<?> clazz) {
        try {
            return clazz.getDeclaredField("delFlag").getAnnotation(TableLogic.class) != null;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("找不到 delFlag 字段：" + clazz.getName(), e);
        }
    }

    private static boolean isNonPersistent(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        TableField tf = field.getAnnotation(TableField.class);
        return tf != null && !tf.exist();
    }
}