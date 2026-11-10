-- 出库单反向生成：记录 ERP 来源单号（销售出库=金蝶发货通知单号），用于幂等与分批扣减
-- 见 docs/superpowers/specs/2026-10-28-yx-wms-sales-delivery-notice-probe.md
ALTER TABLE wms_outbound_order
    ADD COLUMN source_bill_no VARCHAR(64) NULL COMMENT 'ERP来源单号（发货通知单 FHTZD…）' AFTER erp_bill_no,
    ADD INDEX idx_wms_outbound_source_bill (source_bill_no);
