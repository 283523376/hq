-- ============================================================================
-- 永鑫WMS · 受托方口径对齐（V2.4）：新增客户档案 + 入库单受托方三列 + 菜单权限
--
-- 背景（2026-11-04 用户拍板）：受托方与金蝶保持一致——金蝶里受托方是「客户」
-- （BD_Customer，受托加工材料入库单头字段 FCustId），**不再是供应商**。
-- 因此 WMS 新增客户档案，受托相关链路一律引用 wms_customer.id。
--
-- 内容：
--   1) 新建 wms_customer（镜像供应商档案；删除语义沿用「删除时间戳」方案）
--   2) wms_inbound_order 增加 consignor_id / consignor_code / consignor_name
--      ——受托单只写这三列，supplier_* 只给外购用（同一张表两种来源，不能混用）
--   3) 「客户档案」菜单 + 4 个按钮权限 + admin 授权
--
-- 说明：
--   * 脚本可重复执行（CREATE TABLE IF NOT EXISTS / 列与索引均带 information_schema 守卫）。
--   * 唯一索引必须把 deleted_at 纳入：否则被删除的客户编码会永久占用编码
--     （与 uk_wms_supplier_code 同款写法，见 V3.9.5_8 的说明）。
--   * 不回填历史受托单：wms_inbound_order 里既有的 supplier_id 指向 wms_supplier，
--     直接拷进 consignor_id 会变成悬空引用（两张表主键语义不同）。
--     本机无受托单据；其它环境如有历史受托单，需先建对应客户档案再人工补 consignor_*。
-- ============================================================================

-- ---------------------------------------------------------------- 1) 客户档案
CREATE TABLE IF NOT EXISTS `wms_customer` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `customer_code` varchar(64)  NOT NULL COMMENT '客户编码（与金蝶 BD_Customer.FNumber 一致）',
  `customer_name` varchar(100) NOT NULL COMMENT '客户名称',
  `contact`       varchar(50)  DEFAULT NULL COMMENT '联系人',
  `phone`         varchar(30)  DEFAULT NULL COMMENT '联系电话',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `deleted_at`    bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_customer_code` (`customer_code`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_customer_name` (`customer_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='客户档案（受托方）';

-- ---------------------------------------------------------------- 2) 入库单受托方三列
SET @col_consignor_id := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_order'
       AND COLUMN_NAME = 'consignor_id'
);
SET @ddl_consignor_id := IF(@col_consignor_id = 0,
    'ALTER TABLE `wms_inbound_order` ADD COLUMN `consignor_id` varchar(36) DEFAULT NULL COMMENT ''受托方ID（wms_customer.id）：仅受托单使用''',
    'SELECT 1');
PREPARE stmt_consignor_id FROM @ddl_consignor_id;
EXECUTE stmt_consignor_id;
DEALLOCATE PREPARE stmt_consignor_id;

SET @col_consignor_code := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_order'
       AND COLUMN_NAME = 'consignor_code'
);
SET @ddl_consignor_code := IF(@col_consignor_code = 0,
    'ALTER TABLE `wms_inbound_order` ADD COLUMN `consignor_code` varchar(64) DEFAULT NULL COMMENT ''受托方编码（冗余，回写金蝶 FCustId）''',
    'SELECT 1');
PREPARE stmt_consignor_code FROM @ddl_consignor_code;
EXECUTE stmt_consignor_code;
DEALLOCATE PREPARE stmt_consignor_code;

SET @col_consignor_name := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_order'
       AND COLUMN_NAME = 'consignor_name'
);
SET @ddl_consignor_name := IF(@col_consignor_name = 0,
    'ALTER TABLE `wms_inbound_order` ADD COLUMN `consignor_name` varchar(100) DEFAULT NULL COMMENT ''受托方名称（冗余，列表展示）''',
    'SELECT 1');
PREPARE stmt_consignor_name FROM @ddl_consignor_name;
EXECUTE stmt_consignor_name;
DEALLOCATE PREPARE stmt_consignor_name;

-- ---------------------------------------------------------------- 3) 客户档案菜单
-- parent 1910000000000000001 = 「基础数据」目录（V3.9.5_2 建，V3.9.5_4 改过名）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000981','1910000000000000001','客户档案','/wms/customer','wms/customer/index',1,'','',1,NULL,'0',2.50,0,'ant-design:team-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- 4) 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000982','1910000000000000981','客户-查询',NULL,NULL,0,NULL,'',2,'wms:customer:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000983','1910000000000000981','客户-新增',NULL,NULL,0,NULL,'',2,'wms:customer:create','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000984','1910000000000000981','客户-编辑',NULL,NULL,0,NULL,'',2,'wms:customer:edit','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000985','1910000000000000981','客户-删除',NULL,NULL,0,NULL,'',2,'wms:customer:delete','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- 5) admin 角色授权
-- admin 角色 id 取自 V3.9.5_2__wms_material.sql
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000981','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000981',NULL,NOW(),'127.0.0.1'),
('1920000000000000982','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000982',NULL,NOW(),'127.0.0.1'),
('1920000000000000983','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000983',NULL,NOW(),'127.0.0.1'),
('1920000000000000984','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000984',NULL,NOW(),'127.0.0.1'),
('1920000000000000985','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000985',NULL,NOW(),'127.0.0.1');
