-- ============================================================================
-- 永鑫WMS · 「逻辑删除 × 唯一索引」统一改为**删除时间戳**语义（Phase A：4 张既有表）
--
-- 问题（本脚本要修的缺陷）：
--   uk_wms_material_code / uk_wms_mat_cat_code / uk_wms_supplier_code / uk_wms_order_no
--   只建在业务编码列上、不含删除标记，而实体带 @TableLogic。两者语义冲突：
--     1) 查重经 MyBatis-Plus 自动加 del_flag=0，看不见被逻辑删除的行
--        → 「查重说可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」；
--     2) 被删除的编码**永久不可复用**；
--     3) wms_material_category 的删除守卫（有无物料/子分类）同样被加了 del_flag=0
--        → 守卫的判断依据与库内实际占用不一致。
--
-- 方案（需求方指定）：**删除时间戳**
--   deleted_at BIGINT，正常为 0，删除时写「删除时刻的 epoch 毫秒」。
--   唯一索引改为 (编码, deleted_at)。因为每次删除的时间戳都不同，
--   同一编码可以反复「删除 → 重建 → 再删除」而永不冲突；
--   同时**保留了逻辑删除的可恢复/可审计特性**（不像物理删除那样一去不返）。
--
-- 本脚本做的事：
--   1) 加 deleted_at BIGINT NOT NULL DEFAULT 0
--   2) 回填：把已有的 del_flag=1 行标为已删除（时间戳取迁移时刻）
--      —— 注意是**保留**这些行，不是删除它们
--   3) 唯一索引由 (编码) 改为 (编码, deleted_at)
--   4) 去掉旧列 del_flag（避免两套删除标记并存）
--
-- 前置校验（本环境已实测，无编码冲突）：
--   被删行的编码在各自表内均唯一，故回填用同一个时间戳不会撞新唯一索引。
--   若在其它环境执行前发现有「同编码多行且均 del_flag=1」，需先按行去重再回填。
--
-- ⚠️ MySQL 的 DDL 非事务性，本脚本多条 ALTER 无法整体回滚；执行后请核对
--    flyway_schema_history.success = 1 且 deleted_at 列与索引均已生效。
-- ============================================================================

-- ---------------------------------------------------------------- 1) 加列
ALTER TABLE `wms_material`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;
ALTER TABLE `wms_material_category`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;
ALTER TABLE `wms_supplier`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;
ALTER TABLE `wms_order`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;

-- ---------------------------------------------------------------- 2) 回填（保留被删行）
UPDATE `wms_material`          SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;
UPDATE `wms_material_category` SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;
UPDATE `wms_supplier`          SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;
UPDATE `wms_order`             SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;

-- ---------------------------------------------------------------- 3) 唯一索引改为 (编码, deleted_at)
ALTER TABLE `wms_material`
  DROP INDEX `uk_wms_material_code`,
  ADD UNIQUE INDEX `uk_wms_material_code` (`material_code`, `deleted_at`) USING BTREE;
ALTER TABLE `wms_material_category`
  DROP INDEX `uk_wms_mat_cat_code`,
  ADD UNIQUE INDEX `uk_wms_mat_cat_code` (`category_code`, `deleted_at`) USING BTREE;
ALTER TABLE `wms_supplier`
  DROP INDEX `uk_wms_supplier_code`,
  ADD UNIQUE INDEX `uk_wms_supplier_code` (`supplier_code`, `deleted_at`) USING BTREE;
ALTER TABLE `wms_order`
  DROP INDEX `uk_wms_order_no`,
  ADD UNIQUE INDEX `uk_wms_order_no` (`order_no`, `deleted_at`) USING BTREE;

-- ---------------------------------------------------------------- 4) 去掉旧列，避免两套删除标记并存
ALTER TABLE `wms_material`          DROP COLUMN `del_flag`;
ALTER TABLE `wms_material_category` DROP COLUMN `del_flag`;
ALTER TABLE `wms_supplier`          DROP COLUMN `del_flag`;
ALTER TABLE `wms_order`             DROP COLUMN `del_flag`;