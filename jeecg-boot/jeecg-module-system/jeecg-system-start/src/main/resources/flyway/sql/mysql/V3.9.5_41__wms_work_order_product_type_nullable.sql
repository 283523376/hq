-- ================================================================
-- 工单产品类型允许为空
-- 背景（2026-10-28 用户口径）：金蝶 ERP 里没有成品/半成品之分——半成品是工序汇报产生的库存，
-- 成品是完工入库产生的库存。工单只是生产任务，本就不携带该属性，产品类型改由「入库来源」判定。
-- ================================================================
SET @pt_nullable := (
    SELECT IS_NULLABLE FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'product_type'
);
SET @ddl_pt := IF(@pt_nullable = 'NO',
    'ALTER TABLE `wms_work_order` MODIFY COLUMN `product_type` varchar(20) NULL COMMENT ''产品类型（可空：由入库来源判定，工序汇报=半成品、完工入库=成品）''',
    'SELECT 1');
PREPARE stmt_pt FROM @ddl_pt;
EXECUTE stmt_pt;
DEALLOCATE PREPARE stmt_pt;
