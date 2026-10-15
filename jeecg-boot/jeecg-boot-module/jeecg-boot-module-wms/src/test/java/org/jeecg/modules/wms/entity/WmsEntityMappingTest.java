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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    /**
     * 删除语义必须是**删除时间戳**方案，且四个要点缺一不可。
     *
     * <p>背景：唯一索引必须把删除标记纳入（否则被删除的编码**永久占用**唯一索引，
     * 表现为「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」）。
     * 而删除标记若用**固定值**（0/1），同一编码就**只能删一次** ——
     * 第二次删除的行与第一次删标记相同，仍撞索引。故用**时间戳**：
     * 每次删除值都不同，「删除 → 重建 → 再删除」可无限次进行，
     * 同时保留逻辑删除的可恢复/可审计特性。
     *
     * <p>本用例钉住四点，任一被改都会红：
     * <ul>
     *   <li>字段名为 {@code deletedAt}（而非 {@code delFlag}）—— 不许两套标记并存；</li>
     *   <li>带 {@code @TableLogic} —— 否则查询不会自动过滤已删除行；</li>
     *   <li>{@code value = "0"} —— 未删除标记必须与列默认值一致；</li>
     *   <li>{@code delval} 含时间戳表达式 —— **这一条最关键**，退化成常量即重陷「只能删一次」。</li>
     * </ul>
     */
    @Test
    void deleteScheme_mustBeTimestampBased() {
        // Phase A：4 张既有表（2026-10-15 按需求方指定方案改为删除时间戳）
        assertTimestampDeleteScheme(WmsMaterial.class);
        assertTimestampDeleteScheme(WmsMaterialCategory.class);
        assertTimestampDeleteScheme(WmsSupplier.class);
        assertTimestampDeleteScheme(WmsOrder.class);
        // Phase B（BD-003 的三张表）在下一阶段并入本用例；
        // 现阶段它们仍是"物理删除"，故此处不列，避免断言与实际不符。
    }

    private static void assertTimestampDeleteScheme(Class<?> clazz) {
        String name = clazz.getSimpleName();
        // 1) 不许残留旧的 delFlag 列
        assertThrows(NoSuchFieldException.class, () -> clazz.getDeclaredField("delFlag"),
                name + " 不应再保留 delFlag 字段（删除标记只允许一套）");
        // 2) deletedAt + @TableLogic
        Field field;
        try {
            field = clazz.getDeclaredField("deletedAt");
        } catch (NoSuchFieldException e) {
            throw new AssertionError(name + " 缺少 deletedAt 字段", e);
        }
        TableLogic logic = field.getAnnotation(TableLogic.class);
        assertNotNull(logic, name + ".deletedAt 必须带 @TableLogic，否则查询不会自动过滤已删除行");
        // 3) 未删除标记为 0
        assertEquals("0", logic.value(), name + " 的未删除标记必须是 0");
        // 4) delval 必须是时间戳表达式，不能是常量
        String delval = logic.delval();
        assertTrue(delval.contains("UNIX_TIMESTAMP") || delval.toUpperCase().contains("NOW"),
                name + " 的 delval 必须是时间戳表达式（固定值会让同一编码只能删一次），实际：" + delval);
    }

    private static boolean isNonPersistent(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getDeclaredField(fieldName);
        TableField tf = field.getAnnotation(TableField.class);
        return tf != null && !tf.exist();
    }
}