-- 技术债 §5-23：改制单 ↔ 产出入库单结构化关联。
--
-- 背景：V3.9.5_47 把 conversion_order_id / source_material_code 建在了 wms_inbound_order /
-- wms_inbound_detail 上，但改制产出实际写入的是 wms_production_inbound —— 列建错了表，
-- 所以此前只能靠 remark='改制单:GZ…' 字符串反查。本脚本补到正确的表上。
-- 原两列保留（历史数据与回滚用），不删。
--
-- 幂等写法与 V3.9.5_47 一致：information_schema 判断 + PREPARE，可重复执行。

SET @col_pi_conv := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'conversion_order_id'
);
SET @ddl_pi_conv := IF(@col_pi_conv = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `conversion_order_id` varchar(36) DEFAULT NULL COMMENT ''改制单ID（wms_conversion_order.id，改制产出关联）''',
    'SELECT 1');
PREPARE stmt_pi_conv FROM @ddl_pi_conv;
EXECUTE stmt_pi_conv;
DEALLOCATE PREPARE stmt_pi_conv;

SET @col_pi_src := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'source_material_code'
);
SET @ddl_pi_src := IF(@col_pi_src = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `source_material_code` varchar(64) DEFAULT NULL COMMENT ''来源物料编码（改制投入物料）''',
    'SELECT 1');
PREPARE stmt_pi_src FROM @ddl_pi_src;
EXECUTE stmt_pi_src;
DEALLOCATE PREPARE stmt_pi_src;

SET @idx_pi_conv := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND INDEX_NAME = 'idx_wms_prod_inbound_conversion'
);
SET @ddl_idx_conv := IF(@idx_pi_conv = 0,
    'ALTER TABLE `wms_production_inbound` ADD INDEX `idx_wms_prod_inbound_conversion` (`conversion_order_id`)',
    'SELECT 1');
PREPARE stmt_idx_conv FROM @ddl_idx_conv;
EXECUTE stmt_idx_conv;
DEALLOCATE PREPARE stmt_idx_conv;
