-- ============================================================================
-- CK-002 销售订单同步连续失败计数（补偿任务达到阈值时补发告警）
-- 说明：脚本可重复执行（information_schema 判存在）
-- ============================================================================

SET @col_sale_fail := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_outbound_order'
       AND COLUMN_NAME = 'sale_sync_fail_count'
);
SET @ddl_sale_fail := IF(@col_sale_fail = 0,
    'ALTER TABLE `wms_outbound_order` ADD COLUMN `sale_sync_fail_count` int NOT NULL DEFAULT 0 COMMENT ''销售订单同步连续失败次数（成功清零）'' AFTER `sale_sync_time`',
    'SELECT 1');
PREPARE stmt_sale_fail FROM @ddl_sale_fail;
EXECUTE stmt_sale_fail;
DEALLOCATE PREPARE stmt_sale_fail;
