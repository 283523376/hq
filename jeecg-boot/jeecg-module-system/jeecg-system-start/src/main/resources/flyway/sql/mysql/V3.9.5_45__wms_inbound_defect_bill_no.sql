-- ================================================================
-- 混合入库单的不良品回写单号
--
-- 背景：金蝶 PRD_INSTOCK 只有良品数量字段。混合单（良品>0 且不良品>0）过去
-- 只回写良品，不良品在金蝶账上漏记。现在为不良品额外开一张其他入库单
-- （STK_Miscellaneous，QTRK…），需要第二个单号列。
--
-- erp_bill_no        → 良品单号（SCRK…）；纯不良品时仍存 QTRK…（历史兼容）
-- defect_erp_bill_no → 不良品单号（QTRK…）；混合单才用
-- ================================================================

ALTER TABLE `wms_production_inbound`
  ADD COLUMN `defect_erp_bill_no` varchar(64) DEFAULT NULL
  COMMENT '不良品回写的金蝶单号（其他入库单 QTRK…）；混合单专用，纯不良品沿用 erp_bill_no'
  AFTER `erp_bill_no`;
