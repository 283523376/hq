-- 纯不良品自动回写金蝶其他出库单：回存金蝶单号（ERP 是单号唯一来源，见 specs/2026-10-28-yx-wms-defect-outbound-erp-design.md）
ALTER TABLE wms_production_inbound
    ADD COLUMN erp_bill_no VARCHAR(64) NULL COMMENT '金蝶单号（其他出库单 QTCK…/生产入库单 SCRK…）' AFTER sync_status;
