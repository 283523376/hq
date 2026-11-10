-- ============================================================================
-- 内容：盘点单号唯一键改为 (stocktake_no, deleted_at)
-- 原因：原 uk 只含 stocktake_no，软删行会永久占用当日序号，
--       删单后当天再建就 Duplicate entry（与 wms_warehouse 的踩坑一致）。
-- ============================================================================
ALTER TABLE `wms_stocktake_order`
    DROP INDEX `uk_wms_stocktake_no`,
    ADD UNIQUE INDEX `uk_wms_stocktake_no` (`stocktake_no`, `deleted_at`);
