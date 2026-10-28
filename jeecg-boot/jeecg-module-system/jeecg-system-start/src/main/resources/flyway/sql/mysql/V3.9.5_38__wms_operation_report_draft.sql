-- ================================================================
-- 工序汇报（SFC_OperationReport）→ WMS 半成品入库草稿
-- 设计：docs/superpowers/specs/2026-10-28-yx-wms-operation-report-ingest-design.md
-- 阶段 C：只落草稿（不分配库位、不下发 WCS、不回写金蝶）
-- ================================================================

-- 工序汇报分录幂等键：金蝶单号#工序序列#工序号。
-- 同一张 GXHB 单有多个分录行（每道工序一行），只用单号去重会把它们并成一条。
SET @col_entry_key := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'erp_entry_key'
);
SET @ddl_entry_key := IF(@col_entry_key = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `erp_entry_key` varchar(128) DEFAULT NULL COMMENT ''工序汇报分录幂等键：单号#工序序列#工序号''',
    'SELECT 1');
PREPARE stmt_entry_key FROM @ddl_entry_key;
EXECUTE stmt_entry_key;
DEALLOCATE PREPARE stmt_entry_key;

-- 幂等：同一分录重复拉取只更新不新建（NULL 不参与唯一约束，历史数据不受影响）
SET @idx_entry_key := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND INDEX_NAME = 'uk_wms_prod_inbound_entry_key'
);
SET @ddl_idx_entry_key := IF(@idx_entry_key = 0,
    'ALTER TABLE `wms_production_inbound` ADD UNIQUE INDEX `uk_wms_prod_inbound_entry_key` (`erp_entry_key`, `deleted_at`)',
    'SELECT 1');
PREPARE stmt_idx FROM @ddl_idx_entry_key;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
