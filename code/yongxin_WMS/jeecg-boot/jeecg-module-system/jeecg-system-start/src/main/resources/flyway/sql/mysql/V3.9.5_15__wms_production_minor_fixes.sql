-- ============================================================================
-- 永鑫WMS · RK-002 审查 Minor 修复
-- 内容：
--   1) wms_production_inbound 增加 sync_fail_count（后台累计失败次数，用于超限告警）
--   2) wms_production_inbound 增加 manual_remark（转人工完成说明）
--   3) 新建启用状态的「成品电路板」分类，并把验收物料 FG001 从已删除分类迁过来
-- 说明：脚本可重复执行（information_schema 判存在 / INSERT IGNORE / 带条件 UPDATE）
-- ============================================================================

-- ------------------------------------------------ 1) 累计同步失败次数
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'sync_fail_count'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `sync_fail_count` int(11) NOT NULL DEFAULT 0 COMMENT ''后台同步累计失败次数（用于超限告警）''',
    'SELECT 1');
PREPARE stmt_col FROM @ddl;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- ------------------------------------------------ 2) 转人工完成说明
SET @col_exists2 := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'manual_remark'
);
SET @ddl2 := IF(@col_exists2 = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `manual_remark` varchar(500) DEFAULT NULL COMMENT ''转人工完成说明''',
    'SELECT 1');
PREPARE stmt_col2 FROM @ddl2;
EXECUTE stmt_col2;
DEALLOCATE PREPARE stmt_col2;

-- ------------------------------------------------ 3) 启用状态的成品分类
INSERT IGNORE INTO `wms_material_category`
    (`id`,`category_code`,`category_name`,`parent_id`,`category_type`,`status`,`has_child`,`deleted_at`,`create_by`,`create_time`)
VALUES
    ('2110885981815907500','CP-DEMO','成品电路板','0','成品','1','0',0,'admin',NOW());

UPDATE `wms_material`
   SET `category_id` = '2110885981815907500'
 WHERE `material_code` = 'FG001'
   AND `deleted_at` = 0
   AND (`category_id` IS NULL OR `category_id` = '2109933941019193346');
