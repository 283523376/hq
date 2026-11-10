-- ============================================================================
-- 内容：主数据 ERP 同步骨架（spec 2026-10-26-yx-wms-master-data-erp-sync-design.md）
--   1) wms_work_order.order_type            工单类型（客户/备库/委外/受托，ERP 同步，缺省空）
--   2) wms_warehouse.erp_stock_code/name    金蝶仓库映射列 + (code, deleted_at) 唯一索引
--   3) wms_material_category.erp_group_*    金蝶物料分组（代表来源）
--   4) 4 条物料分类种子（半成品/初级产成品/涂层产成品/产成品）
-- 说明：分类种子用固定 id，INSERT IGNORE 保证重复执行不报错；
--       ALTER 语句按既有迁移惯例只执行一次（Flyway 版本控制）。
-- ============================================================================

ALTER TABLE `wms_work_order`
    ADD COLUMN `order_type` varchar(20) NULL COMMENT '工单类型：客户订单/备库订单/委外订单/受托订单（ERP 同步，缺省空）';

ALTER TABLE `wms_warehouse`
    ADD COLUMN `erp_stock_code` varchar(50) NULL COMMENT '金蝶仓库编码（BD_STOCK.FNumber）',
    ADD COLUMN `erp_stock_name` varchar(100) NULL COMMENT '金蝶仓库名称',
    ADD UNIQUE INDEX `uk_wms_warehouse_erp` (`erp_stock_code`, `deleted_at`);

ALTER TABLE `wms_material_category`
    ADD COLUMN `erp_group_number` varchar(100) NULL COMMENT '金蝶物料分组编码（代表来源）',
    ADD COLUMN `erp_group_name` varchar(200) NULL COMMENT '金蝶物料分组名称（代表来源）';

INSERT IGNORE INTO `wms_material_category`
    (`id`, `category_code`, `category_name`, `parent_id`, `category_type`, `status`)
VALUES
    ('CATHALF', 'HALF', '半成品',     '0', '半成品',     '1'),
    ('CATCJ',   'CJ',   '初级产成品', '0', '初级产成品', '1'),
    ('CATTC',   'TC',   '涂层产成品', '0', '涂层产成品', '1'),
    ('CATCP',   'CP',   '产成品',     '0', '产成品',     '1');
