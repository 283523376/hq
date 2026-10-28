-- 「ERP 单号为准」改造：领料/销售出库单回存金蝶单号（specs/2026-10-28-yx-wms-erp-source-of-truth-billno-design.md）
ALTER TABLE wms_outbound_order
    ADD COLUMN erp_bill_no VARCHAR(64) NULL COMMENT '金蝶单号（领料 SOUT…/销售出库 XSCK…）' AFTER sync_fail_count;
