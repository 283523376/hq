package org.jeecg.modules.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import org.jeecg.modules.wms.mapper.WmsConversionDetailMapper;
import org.jeecg.modules.wms.mapper.WmsConversionOrderMapper;
import org.jeecg.modules.wms.mapper.WmsDefectRecordMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V2.4 受托库存 / 改制单 / 不良记录：迁移脚本与实体映射的守卫用例。
 *
 * <p>这些改动都是「表结构先行」：列名、唯一键、重复校验入口一旦写错，
 * 后续服务层要到运行期才会暴露，且库存唯一键失效是**静默**的数据污染。
 * 本用例直接读取 Flyway 脚本文本，把字段名、六列唯一键、迁移前重复守卫、
 * 菜单与权限码钉死在编译后的 50 毫秒内。
 *
 * @Author: 永鑫WMS
 * @Date: 2026-11-04
 */
class WmsConsignedEntityTest {

    private static final String SCRIPT_NAME = "V3.9.5_47__wms_consigned_inventory_and_gap.sql";

    /** 六列唯一键：自有/受托、不同受托方之间不得被合并。 */
    private static final String SIX_COLUMN_INDEX_DDL =
            "ADD UNIQUE INDEX `uk_wms_inv_mat_loc_batch_container` "
                    + "(`material_id`, `location_id`, `batch_no`, `container_code`, `ownership_type`, `consignor_id`)";

    private static final String[] GAP_PERMISSIONS = {
            "wms:inbound:consigned",
            "wms:conversion:view",
            "wms:conversion:create",
            "wms:conversion:execute",
            "wms:conversion:cancel",
            "wms:defect:inbound",
            "wms:outbound:pick:cancel"
    };

    @Test
    void flywayScript_mustExistAndBeReadable() {
        assertTrue(Files.isRegularFile(scriptPath()), "迁移脚本不存在：" + SCRIPT_NAME);
    }

    @Test
    void inventory_mustCarryOwnershipAndConsignorColumns() {
        String sql = readScript();
        assertTrue(sql.contains("`ownership_type`"), "缺少 wms_inventory.ownership_type");
        assertTrue(sql.contains("`consignor_id`"), "缺少 wms_inventory.consignor_id");
        assertTrue(sql.contains("DEFAULT ''OWNED''"),
                "ownership_type 需以 OWNED 为默认值（存量数据即自有；动态 DDL 内层引号写成两个单引号）");
    }

    @Test
    void uniqueIndex_mustBeRebuiltToSixColumns() {
        String sql = readScript();
        assertTrue(sql.contains("DROP INDEX `uk_wms_inv_mat_loc_batch_container`"),
                "重建六列唯一键前必须先删除旧的四列唯一键");
        assertTrue(sql.contains(SIX_COLUMN_INDEX_DDL), "六列唯一键定义不符：" + SIX_COLUMN_INDEX_DDL);
    }

    /**
     * 守卫必须排在重建唯一键之前：存量若已有重复行，直接 ALTER 会以原始 SQL 异常中止，
     * 运维看不到「哪一组重复」；这里要求先用 GROUP BY + HAVING 找出重复并 SIGNAL。
     */
    @Test
    void duplicateGuard_mustRunBeforeIndexRebuild() {
        String sql = readScript();
        int guard = sql.indexOf("HAVING COUNT(*) > 1");
        int rebuild = sql.indexOf("ADD UNIQUE INDEX `uk_wms_inv_mat_loc_batch_container`");

        assertTrue(guard > 0, "缺少重复校验（SELECT COUNT(*) ... GROUP BY ... HAVING COUNT(*) > 1）");
        assertTrue(rebuild > 0, "缺少六列唯一键重建语句");
        assertTrue(guard < rebuild, "重复校验必须在重建唯一键之前执行");
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"), "发现重复行必须以 SIGNAL SQLSTATE '45000' 中止迁移");
        assertTrue(sql.contains("GROUP BY `material_id`, `location_id`, `batch_no`, `container_code`, `ownership_type`, `consignor_id`"),
                "重复校验的分组维度必须与六列唯一键一致");
    }

    @Test
    void inboundAndOutboundColumns_mustBeAdded() {
        String sql = readScript();
        // 入库单：来源类型 + 改制单关联
        assertTrue(sql.contains("`source_type`"), "缺少 wms_inbound_order.source_type");
        assertTrue(sql.contains("`conversion_order_id`"), "缺少 wms_inbound_order.conversion_order_id");
        // 入库明细：来源物料编码（大改小投入）
        assertTrue(sql.contains("`source_material_code`"), "缺少 wms_inbound_detail.source_material_code");
        // 出库单：领料人 + 设备 + 取消原因（CK-001 领料动作细化）
        assertTrue(sql.contains("`picker_id`"), "缺少 wms_outbound_order.picker_id");
        assertTrue(sql.contains("`picker_name`"), "缺少 wms_outbound_order.picker_name");
        assertTrue(sql.contains("`device_id`"), "缺少 wms_outbound_order.device_id");
        assertTrue(sql.contains("`cancel_reason`"), "缺少 wms_outbound_order.cancel_reason");
    }

    @Test
    void gapTables_mustBeCreated() {
        String sql = readScript();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `wms_conversion_order`"), "缺少 wms_conversion_order 建表");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `wms_conversion_detail`"), "缺少 wms_conversion_detail 建表");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `wms_defect_record`"), "缺少 wms_defect_record 建表");

        // 改制单核心列
        for (String col : new String[]{"conversion_no", "production_order_no", "input_material_code",
                "input_batch_no", "input_qty", "output_material_code", "output_batch_no", "output_qty",
                "status", "operator", "erp_bill_no"}) {
            assertTrue(sql.contains("`" + col + "`"), "wms_conversion_order 缺少列 " + col);
        }
        // 不良记录核心列
        for (String col : new String[]{"source_type", "source_bill_no", "material_code", "batch_no",
                "qty", "reason", "disposal_type", "return_bill_no", "status"}) {
            assertTrue(sql.contains("`" + col + "`"), "wms_defect_record 缺少列 " + col);
        }
    }

    @Test
    void supplierMenu_mustBeRestoredNotDuplicated() {
        String sql = readScript();
        assertTrue(sql.contains("`url` = '/wms/supplier'"), "恢复供应商入口必须按 url 定位既有菜单");
        assertTrue(sql.contains("SET `del_flag` = 0"), "供应商入口恢复方式应为 del_flag 改回 0");
        assertEquals(1, countOccurrences(sql, "'/wms/supplier'"),
                "供应商入口只允许 UPDATE 一处，不应新建重复菜单");
        assertFalse(sql.contains("('1910000000000000021'"),
                "不得重新 INSERT 既有供应商菜单（1910000000000000021）");
    }

    @Test
    void newMenusAndPermissions_mustBeInserted() {
        String sql = readScript();
        assertTrue(sql.contains("'外购/受托原材料入库'"), "缺少外购/受托原材料入库菜单");
        assertTrue(sql.contains("'改制单'"), "缺少改制单菜单");
        for (String perms : GAP_PERMISSIONS) {
            assertTrue(sql.contains("'" + perms + "'"), "缺少权限码 " + perms);
        }
        // 权限码必须挂在 menu_type=2（按钮权限）行上
        int firstButton = sql.indexOf(",2,'wms:");
        assertTrue(firstButton > 0, "新增权限码必须以 menu_type=2 的按钮权限插入");
        // 每个新菜单/按钮（0971~0979）都要有 admin 角色授权
        for (int i = 971; i <= 979; i++) {
            String id = "1910000000000000" + i;
            assertTrue(countOccurrences(sql, "'" + id + "'") >= 2,
                    "菜单/按钮 " + id + " 缺少 admin 角色授权（sys_role_permission）");
        }
        assertTrue(sql.contains("'f6817f48af4fb3af11b9e8bf182f618b'"), "缺少 admin 角色 ID");
    }

    @Test
    void newEntities_mustMapExpectedTables() {
        assertEquals("wms_conversion_order", WmsConversionOrder.class.getAnnotation(TableName.class).value());
        assertEquals("wms_conversion_detail", WmsConversionDetail.class.getAnnotation(TableName.class).value());
        assertEquals("wms_defect_record", WmsDefectRecord.class.getAnnotation(TableName.class).value());
    }

    /** 本任务新增表均不做逻辑删除：没有 deleted_at，也不应出现 @TableLogic。 */
    @Test
    void newEntities_mustNotDeclareLogicalDelete() {
        for (Class<?> clazz : new Class<?>[]{WmsConversionOrder.class, WmsConversionDetail.class, WmsDefectRecord.class}) {
            assertThrows(NoSuchFieldException.class, () -> clazz.getDeclaredField("deletedAt"),
                    clazz.getSimpleName() + " 不应声明 deletedAt（本任务新表无逻辑删除）");
        }
    }

    @Test
    void extendedEntities_mustDeclareGapFields() throws Exception {
        assertField(WmsInventory.class, "ownershipType");
        assertField(WmsInventory.class, "consignorId");

        assertField(WmsInboundOrder.class, "sourceType");
        assertField(WmsInboundOrder.class, "conversionOrderId");

        assertField(WmsInboundDetail.class, "sourceMaterialCode");

        assertField(WmsOutboundOrder.class, "pickerId");
        assertField(WmsOutboundOrder.class, "pickerName");
        assertField(WmsOutboundOrder.class, "deviceId");
        assertField(WmsOutboundOrder.class, "cancelReason");
    }

    @Test
    void newMappers_mustBePresent() {
        assertNotNull(WmsConversionOrderMapper.class);
        assertNotNull(WmsConversionDetailMapper.class);
        assertNotNull(WmsDefectRecordMapper.class);
    }

    private static void assertField(Class<?> clazz, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        assertNotNull(field.getType(), clazz.getSimpleName() + "." + name);
    }

    private static Path scriptPath() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("jeecg-module-system/jeecg-system-start/src/main/resources/flyway/sql/mysql/" + SCRIPT_NAME);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Paths.get(SCRIPT_NAME);
    }

    private static String readScript() {
        try {
            return Files.readString(scriptPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取迁移脚本失败：" + scriptPath(), e);
        }
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }
}
