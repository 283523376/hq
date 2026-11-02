-- ================================================================
-- XT-002 验证 —— 回滚 SQL（2026-11-02 更新版）
--
-- 背景：作废链路已在真账套端到端验证通过（见验收报告 §3.5）。
--   金蝶侧 MO1012616533 = 现成的「已作废」样本，**建议保留**，
--   以后回归直接用，不必再造（造样本要删 PPBOM，有操作成本）。
--
-- 本文件清理的是 **WMS 侧** 两张验证单：
--   MO1012603866 —— 早期同步验证用（金蝶侧已删）
--   MO1012616533 —— 作废链路验证用（金蝶侧保留作样本）
--
-- 请手工执行；执行前先跑注释里的 SELECT 核对。
-- ================================================================

-- 0) 执行前核对
-- SELECT work_order_no, work_order_status FROM wms_work_order
--  WHERE work_order_no IN ('MO1012603866', 'MO1012616533');
-- SELECT biz_no, action, result FROM wms_audit_log
--  WHERE biz_no IN ('MO1012603866', 'MO1012616533') ORDER BY create_time;

-- 1) 清理 WMS 审计行（这两张测试单的留痕）
DELETE FROM wms_audit_log WHERE biz_no IN ('MO1012603866', 'MO1012616533');

-- 2) 清理 WMS 工单（两张都是验证明细，无入库/出库引用）
DELETE FROM wms_work_order WHERE work_order_no IN ('MO1012603866', 'MO1012616533');

-- 3) 保留：MO1012615492 的「同步落地」留痕是真实操作记录，不要删。

-- 4) 若曾把保留期配置改过，恢复默认
-- UPDATE wms_system_config SET config_value = '365' WHERE config_key = 'audit.retention.days';
