-- ================================================================
-- 生产入库单备注列（改制单溯源用）
--
-- 背景：大改小改制单完成时经 RK-002 通道生成生产入库单，两单之间的溯源
-- 关联靠入库单备注「改制单:<改制单号>」。wms_production_inbound 建表时
-- 没有通用备注列（V3.9.5_13 的 remark 列属于 wms_work_order），故补一列。
--
-- 约定：与 V3.9.5_47 相同的 information_schema 守卫，可重复执行。
-- ================================================================

SET @col_prod_remark := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_production_inbound'
       AND COLUMN_NAME = 'remark'
);
SET @ddl_prod_remark := IF(@col_prod_remark = 0,
    'ALTER TABLE `wms_production_inbound` ADD COLUMN `remark` varchar(500) DEFAULT NULL COMMENT ''备注（改制单来源等溯源信息，如：改制单:GZ…）'' AFTER `manual_remark`',
    'SELECT 1');
PREPARE stmt_prod_remark FROM @ddl_prod_remark;
EXECUTE stmt_prod_remark;
DEALLOCATE PREPARE stmt_prod_remark;
