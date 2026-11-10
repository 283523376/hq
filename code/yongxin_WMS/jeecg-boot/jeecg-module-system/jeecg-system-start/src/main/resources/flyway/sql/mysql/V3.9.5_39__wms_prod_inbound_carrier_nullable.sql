-- ================================================================
-- 生产入库：载具编码允许为空
-- 背景（2026-10-28 阶段 C）：工序汇报落进来的**半成品入库草稿**还没有载具
-- （载具是 WCS 上架时才绑定的），原表 carrier_code NOT NULL 无默认值，插入直接失败：
--   Field 'carrier_code' doesn't have a default value
-- ================================================================
SET @carrier_nullable := (
    SELECT IS_NULLABLE FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'carrier_code'
);
SET @ddl_carrier := IF(@carrier_nullable = 'NO',
    'ALTER TABLE `wms_production_inbound` MODIFY COLUMN `carrier_code` varchar(64) NULL COMMENT ''载具编码（WCS 上架时绑定；草稿阶段为空）''',
    'SELECT 1');
PREPARE stmt_carrier FROM @ddl_carrier;
EXECUTE stmt_carrier;
DEALLOCATE PREPARE stmt_carrier;
