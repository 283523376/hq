-- ============================================================================
-- 内容：外协/样品出库（spec 2026-10-26-outbound-subcontract-sample-stocktake §4.1）
--   wms_outbound_order 增加金蝶委外订单号与回写单据号
-- 说明：外协出库单以 erp_sub_order_no 关联金蝶 SUB_SUBREQORDER.FBillNo；
--       erp_mis_bill_no 预留给样品出库回写生成的金蝶单据号（E5 就位后使用）。
-- ============================================================================

ALTER TABLE `wms_outbound_order`
    ADD COLUMN `erp_sub_order_no` varchar(50) NULL COMMENT '金蝶委外订单号（SUB_SUBREQORDER.FBillNo）',
    ADD COLUMN `erp_mis_bill_no` varchar(50) NULL COMMENT '回写生成的金蝶单据号',
    ADD INDEX `idx_wms_outbound_erp_sub` (`erp_sub_order_no`);
