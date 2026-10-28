-- ================================================================
-- KC-001 库存预警：库存首次入库时间 + 预警规则 + 预警单
-- 设计：docs/superpowers/specs/2026-10-28-yx-wms-inventory-warning-batch-design.md
-- ================================================================

-- ------------------------------------------------ 1) wms_inventory 补「批次首次入库时间」
SET @col_first_in := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND COLUMN_NAME = 'first_inbound_time'
);
SET @ddl_first_in := IF(@col_first_in = 0,
    'ALTER TABLE `wms_inventory` ADD COLUMN `first_inbound_time` datetime DEFAULT NULL COMMENT ''批次首次入库时间（超期预警起算点）''',
    'SELECT 1');
PREPARE stmt_first_in FROM @ddl_first_in;
EXECUTE stmt_first_in;
DEALLOCATE PREPARE stmt_first_in;

-- 存量回填：行创建时间即该批次首次进入 WMS 的时间（调拨/盘盈行建时会显式继承或取 NOW）
UPDATE `wms_inventory`
   SET `first_inbound_time` = COALESCE(`create_time`, NOW())
 WHERE `first_inbound_time` IS NULL;

-- ------------------------------------------------ 2) 预警规则
CREATE TABLE IF NOT EXISTS `wms_warning_rule` (
  `id`            varchar(32)    NOT NULL COMMENT '主键',
  `rule_name`     varchar(64)    NOT NULL COMMENT '规则名称',
  `rule_type`     varchar(20)    NOT NULL COMMENT '规则类型：LOW_STOCK 库存下限 / OVERDUE 存放超期',
  `scope_type`    varchar(20)    NOT NULL COMMENT '作用域：MATERIAL 物料 / CATEGORY 分类 / ALL 全部',
  `material_id`   varchar(32)    DEFAULT NULL COMMENT '物料ID（scope=MATERIAL 时必填）',
  `category_id`   varchar(32)    DEFAULT NULL COMMENT '物料分类ID（scope=CATEGORY 时必填）',
  `warehouse_id`  varchar(32)    DEFAULT NULL COMMENT '限定库区ID（空=全部库区）',
  `threshold_qty` decimal(12,3)  DEFAULT NULL COMMENT '库存下限（rule_type=LOW_STOCK 时必填）',
  `overdue_days`  int            DEFAULT NULL COMMENT '存放超期天数（rule_type=OVERDUE 时必填）',
  `enabled`       tinyint        NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
  `remark`        varchar(255)   DEFAULT NULL COMMENT '备注',
  `create_by`     varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`    bigint         NOT NULL DEFAULT 0 COMMENT '删除时间戳（0=未删除）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_warn_rule_name` (`rule_name`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_warn_rule_type` (`rule_type`, `enabled`) USING BTREE,
  INDEX `idx_wms_warn_rule_scope` (`scope_type`, `material_id`, `category_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库存预警规则';

-- ------------------------------------------------ 3) 预警单
CREATE TABLE IF NOT EXISTS `wms_inventory_warning` (
  `id`                 varchar(32)    NOT NULL COMMENT '主键',
  `warning_no`         varchar(64)    NOT NULL COMMENT '预警单号',
  `rule_id`            varchar(32)    DEFAULT NULL COMMENT '规则ID',
  `rule_name`          varchar(64)    DEFAULT NULL COMMENT '规则名称快照',
  `rule_type`          varchar(20)    NOT NULL COMMENT '规则类型快照：LOW_STOCK / OVERDUE',
  `material_id`        varchar(32)    NOT NULL COMMENT '物料ID',
  `material_code`      varchar(64)    DEFAULT NULL COMMENT '物料编码快照',
  `material_name`      varchar(255)   DEFAULT NULL COMMENT '物料名称快照',
  `warehouse_id`       varchar(32)    DEFAULT NULL COMMENT '库区ID',
  `location_id`        varchar(32)    DEFAULT NULL COMMENT '库位ID（超期预警）',
  `location_code`      varchar(64)    DEFAULT NULL COMMENT '库位编码快照',
  `batch_no`           varchar(64)    DEFAULT NULL COMMENT '批次号（超期预警）',
  `container_code`     varchar(64)    DEFAULT NULL COMMENT '容器/料箱编码',
  `current_qty`        decimal(12,3)  NOT NULL DEFAULT 0 COMMENT '触发时数量：下限=可用量，超期=库存量',
  `threshold_qty`      decimal(12,3)  DEFAULT NULL COMMENT '阈值快照',
  `overdue_days`       int            DEFAULT NULL COMMENT '超期天数快照',
  `first_inbound_time` datetime       DEFAULT NULL COMMENT '首次入库时间快照',
  `status`             varchar(20)    NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待处理 / RESOLVED 已解除',
  `dedup_key`          varchar(255)   NOT NULL COMMENT '合并去重键；解除后追加 #R#时间戳',
  `hit_count`          int            NOT NULL DEFAULT 1 COMMENT '命中次数',
  `last_hit_time`      datetime       DEFAULT NULL COMMENT '最近命中时间',
  `handle_type`        varchar(20)    DEFAULT NULL COMMENT '处理方式：REPLENISH 补料 / CLEAR 清理 / OTHER 其他',
  `handle_remark`      varchar(500)   DEFAULT NULL COMMENT '处理说明',
  `handled_by`         varchar(50)    DEFAULT NULL COMMENT '处理人',
  `handle_time`        datetime       DEFAULT NULL COMMENT '处理时间',
  `create_by`          varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`        datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`          varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`        datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`         bigint         NOT NULL DEFAULT 0 COMMENT '删除时间戳（0=未删除）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_warn_no` (`warning_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_warn_dedup` (`dedup_key`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_warn_status` (`status`, `rule_type`) USING BTREE,
  INDEX `idx_wms_warn_material` (`material_id`) USING BTREE,
  INDEX `idx_wms_warn_batch` (`batch_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库存预警单';

-- ------------------------------------------------ 4) 菜单：库存预警（sort=95）+ 批次追溯（sort=96）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000601','','库存预警','/wms/warning','layouts/default/index',1,'','/wms/warning/list',0,NULL,'0',95.00,0,'ant-design:alert-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000602','1910000000000000601','预警处理','/wms/warning/list','wms/warning/index',1,'','',1,NULL,'0',1.00,0,'ant-design:notification-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000621','','批次追溯','/wms/inventory','layouts/default/index',1,'','/wms/inventory/trace',0,NULL,'0',96.00,0,'ant-design:search-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000622','1910000000000000621','批次查询','/wms/inventory/trace','wms/inventory/index',1,'','',1,NULL,'0',1.00,0,'ant-design:profile-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 5) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000611','1910000000000000602','预警-查询',NULL,NULL,0,NULL,'',2,'wms:warning:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000612','1910000000000000602','预警-新建规则',NULL,NULL,0,NULL,'',2,'wms:warning:add','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000613','1910000000000000602','预警-编辑规则',NULL,NULL,0,NULL,'',2,'wms:warning:edit','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000614','1910000000000000602','预警-删除规则',NULL,NULL,0,NULL,'',2,'wms:warning:delete','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000615','1910000000000000602','预警-处理',NULL,NULL,0,NULL,'',2,'wms:warning:handle','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000616','1910000000000000602','预警-立即扫描',NULL,NULL,0,NULL,'',2,'wms:warning:scan','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000631','1910000000000000622','批次-查询',NULL,NULL,0,NULL,'',2,'wms:inventory:batch','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 6) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000601','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000601',NULL,NOW(),'127.0.0.1'),
('1920000000000000602','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000602',NULL,NOW(),'127.0.0.1'),
('1920000000000000611','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000611',NULL,NOW(),'127.0.0.1'),
('1920000000000000612','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000612',NULL,NOW(),'127.0.0.1'),
('1920000000000000613','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000613',NULL,NOW(),'127.0.0.1'),
('1920000000000000614','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000614',NULL,NOW(),'127.0.0.1'),
('1920000000000000615','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000615',NULL,NOW(),'127.0.0.1'),
('1920000000000000616','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000616',NULL,NOW(),'127.0.0.1'),
('1920000000000000621','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000621',NULL,NOW(),'127.0.0.1'),
('1920000000000000622','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000622',NULL,NOW(),'127.0.0.1'),
('1920000000000000631','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000631',NULL,NOW(),'127.0.0.1');
