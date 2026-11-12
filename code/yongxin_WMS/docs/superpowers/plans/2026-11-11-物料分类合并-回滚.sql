-- 物料分类合并 —— 回滚脚本
-- 对应迁移：V3.9.5_60__wms_material_category_merge.sql
-- 前提：备份表 wms_material_merge_bak / wms_material_category_merge_bak 未被清理。

-- 1) 物料挂载改回旧分类（只回滚仍挂在新分类上的行，避免覆盖迁移后的新增）
UPDATE `wms_material` m
JOIN `wms_material_merge_bak` b ON b.material_id = m.id
SET m.category_id = b.old_category_id
WHERE m.category_id = b.new_category_id;

-- 2) 被合并分类恢复
-- ⚠ 注意（QA M10）：本步会把备份表里的**全部**行恢复，不区分「合并时删除」与
-- 「合并后又经页面合法删除」的分类。回滚是人工操作，执行前先核对：
--   SELECT * FROM wms_material_category_merge_bak b JOIN wms_material_category c
--     ON c.id = b.category_id WHERE c.deleted_at <> 0 AND c.deleted_at > b.create_time;
-- 若存在合并后被合法删除的分类，应从下面 UPDATE 的结果中把它们重新置回删除态。
UPDATE `wms_material_category` c
JOIN `wms_material_category_merge_bak` b ON b.category_id = c.id
SET c.deleted_at = 0
WHERE c.deleted_at <> 0;

-- 3) 清空映射表：该表由本次合并引入，合并后的所有行（含同步后续补写的、create_by 不是 'merge' 的行）
--    都应清掉；只删 create_by='merge' 会残留同步写入的行，导致回滚后分组仍指向存活分类、
--    物料不随分类恢复（2026-11-12 审计 R5）。
DELETE FROM `wms_material_category_group`;

-- 说明：映射表本身保留（后续重跑迁移会重新写入）；如需彻底删除可执行
-- DROP TABLE IF EXISTS `wms_material_category_group`;
--
-- 已知边界：回滚恢复被合并分类的 deleted_at、把物料改回原分类，但**不还原存活分类被同步
-- 改掉的 category_type**（备份表未记录存活分类的类型原值）；合并本身不改类型，只有同步才会。
