-- ============================================================================
-- 永鑫WMS · BD-003 配置表删除语义修正：清理历史逻辑删除行
--
-- 背景（端到端验收抓到的缺陷）：
--   V3.9.5_5 建的三张配置表上，唯一索引只建在业务列上、不含 del_flag：
--     uk_wms_warehouse_code (warehouse_code)
--     uk_wms_device_code    (device_code)
--     uk_wms_location_code  (location_code)
--     uk_wms_location_grid  (warehouse_id, row_no, col_no, layer_no)
--   而实体原先带 @TableLogic。两者语义冲突：
--     1) checkCodeExists 经 MyBatis-Plus 自动加 del_flag=0，看不见被逻辑删除的行，
--        于是「查重说编码可用 → 保存撞唯一索引 → 用户看到原始 SQL 异常」；
--     2) 被删除的编码/网格位**永久不可复用**；
--     3) 最要命的一条：库位被逻辑删除后重跑批量生成，那几行会被 INSERT IGNORE
--        静默跳过（计入 skipped）却不会被恢复，列表里也看不见 ——
--        直接破坏「重跑同范围可安全续做」这条承诺，且不留痕迹。
--
-- 修正：三张配置表改为**物理删除**（去掉实体的 @TableLogic）。
--   安全由删除守卫承担（有库位/有设备不许删库区；占用或故障不许删库位），
--   留痕由 @AutoLog 的操作日志承担。
--
-- 本脚本：把已存在的逻辑删除行真删掉 —— 否则去掉 @TableLogic 后它们会「复活」
--   出现在列表里，且继续占着唯一索引。
-- 幂等：DELETE ... WHERE del_flag = 1，重复执行无副作用（第二次已无匹配行）。
-- ============================================================================

-- 顺序：先库位（引用库区）与设备，再库区。本脚本只删 del_flag=1 的行，
-- 不涉及仍被引用的现存记录，故顺序仅为了可读性。
DELETE FROM `wms_location`  WHERE `del_flag` = 1;
DELETE FROM `wms_device`    WHERE `del_flag` = 1;
DELETE FROM `wms_warehouse` WHERE `del_flag` = 1;