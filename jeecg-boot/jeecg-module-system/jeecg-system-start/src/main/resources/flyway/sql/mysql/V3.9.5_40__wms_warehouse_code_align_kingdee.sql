-- ================================================================
-- 仓库编码与金蝶 ERP 1:1 对齐（2026-10-28 用户决策：WMS 仓库编码必须等于金蝶 FNumber）
--
-- 只改 code，不动 id —— 库位/库存/单据全挂在 warehouse_id 上，改码不影响引用。
-- 每个改名都带双向守卫：源存在且目标不存在才执行，重复跑或目标已存在时自动跳过。
-- ================================================================

-- LB（半成品立体库） → D.02
SET @src_lb := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'LB' AND `deleted_at` = 0);
SET @dst_lb := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'D.02' AND `deleted_at` = 0);
SET @ddl_lb := IF(@src_lb = 1 AND @dst_lb = 0,
    'UPDATE `wms_warehouse` SET `warehouse_code` = ''D.02'', `erp_stock_code` = ''D.02'', `update_time` = NOW() WHERE `warehouse_code` = ''LB'' AND `deleted_at` = 0',
    'SELECT 1');
PREPARE stmt_lb FROM @ddl_lb;
EXECUTE stmt_lb;
DEALLOCATE PREPARE stmt_lb;

-- MJ（成品敏捷仓） → D.01
SET @src_mj := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'MJ' AND `deleted_at` = 0);
SET @dst_mj := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'D.01' AND `deleted_at` = 0);
SET @ddl_mj := IF(@src_mj = 1 AND @dst_mj = 0,
    'UPDATE `wms_warehouse` SET `warehouse_code` = ''D.01'', `erp_stock_code` = ''D.01'', `update_time` = NOW() WHERE `warehouse_code` = ''MJ'' AND `deleted_at` = 0',
    'SELECT 1');
PREPARE stmt_mj FROM @ddl_mj;
EXECUTE stmt_mj;
DEALLOCATE PREPARE stmt_mj;

-- FPC01（废品仓） → F.03
SET @src_fp := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'FPC01' AND `deleted_at` = 0);
SET @dst_fp := (SELECT COUNT(*) FROM `wms_warehouse` WHERE `warehouse_code` = 'F.03' AND `deleted_at` = 0);
SET @ddl_fp := IF(@src_fp = 1 AND @dst_fp = 0,
    'UPDATE `wms_warehouse` SET `warehouse_code` = ''F.03'', `erp_stock_code` = ''F.03'', `update_time` = NOW() WHERE `warehouse_code` = ''FPC01'' AND `deleted_at` = 0',
    'SELECT 1');
PREPARE stmt_fp FROM @ddl_fp;
EXECUTE stmt_fp;
DEALLOCATE PREPARE stmt_fp;

-- CJ / TC 是早期手工建档的占位仓（0 库位 0 库存），金蝶侧已有同义仓（TC ↔ X.2.4 涂层产成品仓）。
-- 按 1:1 口径停用，不物理删除（历史单据可能引用）。
UPDATE `wms_warehouse` w
   SET w.`status` = '0', w.`update_time` = NOW()
 WHERE w.`warehouse_code` IN ('CJ', 'TC')
   AND w.`deleted_at` = 0
   AND NOT EXISTS (SELECT 1 FROM `wms_location` l WHERE l.`warehouse_id` = w.`id` AND l.`deleted_at` = 0);
