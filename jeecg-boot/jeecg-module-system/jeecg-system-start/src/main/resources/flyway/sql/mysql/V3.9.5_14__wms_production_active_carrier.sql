-- ============================================================================
-- 永鑫WMS · RK-002 生产入库并发加固
-- 内容：为「未完结载具」增加数据库唯一约束
--   1) 生成列 active_carrier：仅在 待上架/执行中/异常待人工 且未删除时等于 carrier_code，
--      终态（已入库/已关闭/已删除）为 NULL；MySQL 唯一索引允许多个 NULL，因此载具可复用。
--   2) 唯一索引 uk_wms_prod_inbound_active_carrier：兜住跨工单并发提交同一载具。
-- 说明：脚本可重复执行（information_schema 判存在后再 ALTER）。
-- ============================================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'active_carrier'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `active_carrier` varchar(64) GENERATED ALWAYS AS (CASE WHEN `deleted_at` = 0 AND `status` IN (''待上架'', ''执行中'', ''异常待人工'') THEN `carrier_code` ELSE NULL END) STORED COMMENT ''活跃载具码（未完结专用，用于唯一约束）''',
    'SELECT 1');
PREPARE stmt_col FROM @ddl;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND INDEX_NAME = 'uk_wms_prod_inbound_active_carrier'
);
SET @ddl_idx := IF(@idx_exists = 0,
    'ALTER TABLE `wms_production_inbound` ADD UNIQUE INDEX `uk_wms_prod_inbound_active_carrier` (`active_carrier`)',
    'SELECT 1');
PREPARE stmt_idx FROM @ddl_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
