-- 物料主数据同步（specs/2026-10-28-yx-wms-material-master-sync-design.md）：
-- kingdee_code 承接原 YAML material-map 的 WMS→金蝶编码映射（ERP 同步物料为恒等映射）；
-- kingdee_product_type 承接原 YAML material-product-type（半成品/成品，金蝶无此概念，人工维护）
ALTER TABLE wms_material
    ADD COLUMN kingdee_code VARCHAR(64) NULL COMMENT '金蝶物料编码（ERP 同步物料=material_code 恒等）' AFTER unit,
    ADD COLUMN kingdee_product_type VARCHAR(20) NULL COMMENT '产品类型（半成品/成品；金蝶无此概念，人工维护）' AFTER kingdee_code;
