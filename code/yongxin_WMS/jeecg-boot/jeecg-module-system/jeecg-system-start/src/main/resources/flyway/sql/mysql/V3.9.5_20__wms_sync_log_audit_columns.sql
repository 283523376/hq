-- ============================================================================
-- 永鑫WMS · wms_sync_log 补齐 JeecgEntity 审计列
-- 原因：WmsSyncLog extends JeecgEntity，MyBatis-Plus insert 会带上 update_by/update_time；
--       V3.9.5_19 的建表仅含 create_by/create_time，导致同步失败日志写入报
--       Unknown column 'update_by' in 'field list'。
-- 说明：可重复执行（information_schema 判存在）。
-- 回滚：
--   ALTER TABLE `wms_sync_log` DROP COLUMN `update_by`, DROP COLUMN `update_time`;
-- ============================================================================

SET @col_update_by := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_sync_log'
       AND COLUMN_NAME = 'update_by'
);
SET @ddl_update_by := IF(@col_update_by = 0,
    'ALTER TABLE `wms_sync_log` ADD COLUMN `update_by` varchar(50) DEFAULT NULL COMMENT ''更新人'' AFTER `create_time`',
    'SELECT 1');
PREPARE stmt FROM @ddl_update_by; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_update_time := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_sync_log'
       AND COLUMN_NAME = 'update_time'
);
SET @ddl_update_time := IF(@col_update_time = 0,
    'ALTER TABLE `wms_sync_log` ADD COLUMN `update_time` datetime DEFAULT NULL COMMENT ''更新时间'' AFTER `update_by`',
    'SELECT 1');
PREPARE stmt FROM @ddl_update_time; EXECUTE stmt; DEALLOCATE PREPARE stmt;
