-- ============================================================================
-- 永鑫WMS · 「逻辑删除 × 唯一索引」统一为**删除时间戳**方案（Phase B：BD-003 的三张表）
--
-- 背景：BD-003 期间这三张表先是用「物理删除」（去掉 @TableLogic）解决的同一缺陷；
--   随后需求方指定统一方案为**删除时间戳**（见 V3.9.5_8 对 4 张既有表的改造）。
--   本脚本把这三张表并入同一方案 —— 否则同一缺陷在同一模块里会留下两套删除语义。
--
-- 方案（与 V3.9.5_8 完全一致）：
--   deleted_at BIGINT，0 = 未删除，删除时写「删除时刻的 epoch 毫秒」；
--   唯一索引把 deleted_at 一起纳入。时间戳每次不同，故
--   「删除 → 重建 → 再删除」可无限次进行；同时保留逻辑删除的可恢复/可审计。
--
-- 本脚本做的事：
--   1) 加 deleted_at BIGINT NOT NULL DEFAULT 0
--   2) 回填（这三张表当前无 del_flag=1 的行，此步为空操作，保留以求脚本形状一致）
--   3) 唯一索引改为含 deleted_at
--   4) 去掉旧列 del_flag
--
-- 对 wms_location 的额外意义：
--   uk_wms_location_grid(warehouse_id,row_no,col_no,layer_no) 也纳入 deleted_at 后，
--   「删掉几个库位 → 重跑批量生成」会**新建**行而不是被 INSERT IGNORE 静默跳过，
--   即批量生成的幂等续做语义与删除语义不再互相干扰。
--
-- ⚠️ MySQL 的 DDL 非事务性；执行后请核对 flyway_schema_history.success = 1 且结构生效。
-- ============================================================================

-- ---------------------------------------------------------------- 1) 加列
ALTER TABLE `wms_warehouse`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;
ALTER TABLE `wms_location`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;
ALTER TABLE `wms_device`
  ADD COLUMN `deleted_at` bigint NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻' AFTER `del_flag`;

-- ---------------------------------------------------------------- 2) 回填（当前为空操作，保留以求形状一致）
UPDATE `wms_warehouse` SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;
UPDATE `wms_location`  SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;
UPDATE `wms_device`    SET `deleted_at` = UNIX_TIMESTAMP(NOW(3)) * 1000 WHERE `del_flag` = 1 AND `deleted_at` = 0;

-- ---------------------------------------------------------------- 3) 唯一索引纳入 deleted_at
ALTER TABLE `wms_warehouse`
  DROP INDEX `uk_wms_warehouse_code`,
  ADD UNIQUE INDEX `uk_wms_warehouse_code` (`warehouse_code`, `deleted_at`) USING BTREE;

ALTER TABLE `wms_location`
  DROP INDEX `uk_wms_location_code`,
  DROP INDEX `uk_wms_location_grid`,
  ADD UNIQUE INDEX `uk_wms_location_code` (`location_code`, `deleted_at`) USING BTREE,
  ADD UNIQUE INDEX `uk_wms_location_grid` (`warehouse_id`, `row_no`, `col_no`, `layer_no`, `deleted_at`) USING BTREE;

ALTER TABLE `wms_device`
  DROP INDEX `uk_wms_device_code`,
  ADD UNIQUE INDEX `uk_wms_device_code` (`device_code`, `deleted_at`) USING BTREE;

-- ---------------------------------------------------------------- 4) 去掉旧列，避免两套删除标记并存
ALTER TABLE `wms_warehouse` DROP COLUMN `del_flag`;
ALTER TABLE `wms_location`  DROP COLUMN `del_flag`;
ALTER TABLE `wms_device`    DROP COLUMN `del_flag`;