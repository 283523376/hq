-- ================================================================
-- XT-002 真账套验证 —— 回滚 SQL（2026-11-02）
--
-- 本次验证**没有改动金蝶任何单据**：PRD_MO 的作废被业务流拦截
-- （A/C 状态均报「单据已存在关联的下游单据，不能作废」），
-- 详见验收报告 §4.1。
--
-- WMS 侧只多了一张为验证同步链路而同步进来的工单 MO1012603866
-- （已确认无入库单、无出库单引用，审计 1 行）。
--
-- 请手工执行；执行前建议先跑一遍注释里的 SELECT 核对。
-- ================================================================

-- 0) 执行前核对
-- SELECT work_order_no, work_order_status, material_code FROM wms_work_order WHERE work_order_no = 'MO1012603866';
-- SELECT COUNT(*) FROM wms_production_inbound WHERE work_order_no = 'MO1012603866';  -- 期望 0
-- SELECT COUNT(*) FROM wms_outbound_order    WHERE work_order_no = 'MO1012603866';  -- 期望 0
-- SELECT id, biz_type, action, channel, create_time FROM wms_audit_log WHERE biz_no = 'MO1012603866';

-- 1) 删除验证用审计行（同步落地留痕）
DELETE FROM wms_audit_log WHERE biz_no = 'MO1012603866';

-- 2) 物理删除该工单（WMS 原本没有这张单）
DELETE FROM wms_work_order WHERE work_order_no = 'MO1012603866';

-- 3) 可选：MO1012615492 的二次同步留痕
--    这张单是 WMS 原有工单，本次只是重复同步了一次（rank-max，状态未变）。
--    留痕本身是真实操作记录，通常应保留；若要清：
-- DELETE FROM wms_audit_log WHERE biz_no = 'MO1012615492' AND action = '同步落地';

-- 4) 若曾把保留期配置改过，恢复默认
-- UPDATE wms_system_config SET config_value = '365' WHERE config_key = 'audit.retention.days';
