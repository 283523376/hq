-- ================================================================
-- 蓝图 V2.4 差距落地（P0）：受托库存模型 + 改制单 / 不良记录表 + 菜单权限
-- 设计：docs/superpowers/specs/2026-11-04-yx-wms-v2.4-gap-implementation-design.md
-- 内容：
--   1) wms_inventory 增加库存所有权/受托方两维，唯一键由四列重建为六列（迁移前重复校验）
--   2) wms_inbound_order / wms_inbound_detail / wms_outbound_order 补齐 V2.4 字段
--   3) 新建 wms_conversion_order / wms_conversion_detail / wms_defect_record
--   4) 恢复「供应商」入口；新增「外购/受托原材料入库」「改制单」菜单与按钮权限
-- 约定：新表均无 deleted_at（不做逻辑删除）；无物理外键；数量 decimal(20,6)。
-- 说明：DDL 均带 information_schema 守卫，可重复执行；唯一键重建前若检出重复行
--       以 SIGNAL SQLSTATE '45000' 中止，避免原始 SQL 异常掩盖数据问题。
-- ================================================================

-- ------------------------------------------------ 1) wms_inventory 所有权/受托方
SET @col_ownership := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND COLUMN_NAME = 'ownership_type'
);
SET @ddl_ownership := IF(@col_ownership = 0,
    'ALTER TABLE `wms_inventory` ADD COLUMN `ownership_type` varchar(16) NOT NULL DEFAULT ''OWNED'' COMMENT ''库存所有权：OWNED 自有 / CONSIGNED 受托'' AFTER `locked_qty`',
    'SELECT 1');
PREPARE stmt_ownership FROM @ddl_ownership;
EXECUTE stmt_ownership;
DEALLOCATE PREPARE stmt_ownership;

-- 受托方用空串而非 NULL：MySQL 唯一索引允许多个 NULL，
-- consignor_id 若可空，自有库存（全部空串语义）的六列唯一键会失效。
SET @col_consignor := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND COLUMN_NAME = 'consignor_id'
);
SET @ddl_consignor := IF(@col_consignor = 0,
    'ALTER TABLE `wms_inventory` ADD COLUMN `consignor_id` varchar(36) NOT NULL DEFAULT '''' COMMENT ''受托方ID（wms_supplier.id）：受托必填，自有空串'' AFTER `ownership_type`',
    'SELECT 1');
PREPARE stmt_consignor FROM @ddl_consignor;
EXECUTE stmt_consignor;
DEALLOCATE PREPARE stmt_consignor;

-- ------------------------------------------------ 2) 唯一键重建前的重复校验
-- 四列旧唯一键保证不了「连同所有权/受托方的六列」唯一吗？保证不了：
-- 受托方可能在 WMS 外部录入中被手工合并，故重建前必须实测一次。
DROP PROCEDURE IF EXISTS `p_wms_inv_uk_six_guard`;
CREATE PROCEDURE `p_wms_inv_uk_six_guard`()
BEGIN
  DECLARE dup_groups INT DEFAULT 0;
  SELECT COUNT(*) INTO dup_groups FROM (
    SELECT 1
      FROM `wms_inventory`
     GROUP BY `material_id`, `location_id`, `batch_no`, `container_code`, `ownership_type`, `consignor_id`
    HAVING COUNT(*) > 1
  ) AS duplicated;
  IF dup_groups > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wms_inventory 存在同一物料/库位/批次/容器/所有权/受托方的重复行，六列唯一键无法建立；请先人工合并库存后再迁移';
  END IF;
END;
CALL `p_wms_inv_uk_six_guard`();
DROP PROCEDURE IF EXISTS `p_wms_inv_uk_six_guard`;

-- ------------------------------------------------ 3) 唯一键重建为六列
SET @old_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'uk_wms_inv_mat_loc_batch_container'
);
SET @ddl_drop := IF(@old_idx > 0,
    'ALTER TABLE `wms_inventory` DROP INDEX `uk_wms_inv_mat_loc_batch_container`',
    'SELECT 1');
PREPARE stmt_drop FROM @ddl_drop;
EXECUTE stmt_drop;
DEALLOCATE PREPARE stmt_drop;

SET @ddl_new_idx :=
    'ALTER TABLE `wms_inventory` ADD UNIQUE INDEX `uk_wms_inv_mat_loc_batch_container` (`material_id`, `location_id`, `batch_no`, `container_code`, `ownership_type`, `consignor_id`)';
PREPARE stmt_new_idx FROM @ddl_new_idx;
EXECUTE stmt_new_idx;
DEALLOCATE PREPARE stmt_new_idx;

-- 受托库存按受托方检索（库存查询默认只显示自有，受托需显式筛选）
SET @idx_ownership := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'idx_wms_inv_ownership'
);
SET @ddl_ownership_idx := IF(@idx_ownership = 0,
    'ALTER TABLE `wms_inventory` ADD INDEX `idx_wms_inv_ownership` (`ownership_type`, `consignor_id`)',
    'SELECT 1');
PREPARE stmt_ownership_idx FROM @ddl_ownership_idx;
EXECUTE stmt_ownership_idx;
DEALLOCATE PREPARE stmt_ownership_idx;

-- ------------------------------------------------ 4) 入库单/明细补齐 V2.4 字段
SET @col_in_source := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_order'
       AND COLUMN_NAME = 'source_type'
);
SET @ddl_in_source := IF(@col_in_source = 0,
    'ALTER TABLE `wms_inbound_order` ADD COLUMN `source_type` varchar(16) DEFAULT NULL COMMENT ''来源类型：PURCHASE 外购 / CONSIGNED 受托''',
    'SELECT 1');
PREPARE stmt_in_source FROM @ddl_in_source;
EXECUTE stmt_in_source;
DEALLOCATE PREPARE stmt_in_source;

SET @col_in_conv := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_order'
       AND COLUMN_NAME = 'conversion_order_id'
);
SET @ddl_in_conv := IF(@col_in_conv = 0,
    'ALTER TABLE `wms_inbound_order` ADD COLUMN `conversion_order_id` varchar(36) DEFAULT NULL COMMENT ''改制单ID（大改小产出关联，wms_conversion_order.id）''',
    'SELECT 1');
PREPARE stmt_in_conv FROM @ddl_in_conv;
EXECUTE stmt_in_conv;
DEALLOCATE PREPARE stmt_in_conv;

SET @col_in_detail := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inbound_detail'
       AND COLUMN_NAME = 'source_material_code'
);
SET @ddl_in_detail := IF(@col_in_detail = 0,
    'ALTER TABLE `wms_inbound_detail` ADD COLUMN `source_material_code` varchar(64) DEFAULT NULL COMMENT ''来源物料编码（改制投入物料）''',
    'SELECT 1');
PREPARE stmt_in_detail FROM @ddl_in_detail;
EXECUTE stmt_in_detail;
DEALLOCATE PREPARE stmt_in_detail;

-- ------------------------------------------------ 5) 出库单补齐领料动作字段
SET @col_picker_id := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_outbound_order'
       AND COLUMN_NAME = 'picker_id'
);
SET @ddl_picker_id := IF(@col_picker_id = 0,
    'ALTER TABLE `wms_outbound_order` ADD COLUMN `picker_id` varchar(36) DEFAULT NULL COMMENT ''领料人ID（当前登录账号）''',
    'SELECT 1');
PREPARE stmt_picker_id FROM @ddl_picker_id;
EXECUTE stmt_picker_id;
DEALLOCATE PREPARE stmt_picker_id;

SET @col_picker_name := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_outbound_order'
       AND COLUMN_NAME = 'picker_name'
);
SET @ddl_picker_name := IF(@col_picker_name = 0,
    'ALTER TABLE `wms_outbound_order` ADD COLUMN `picker_name` varchar(64) DEFAULT NULL COMMENT ''领料人姓名''',
    'SELECT 1');
PREPARE stmt_picker_name FROM @ddl_picker_name;
EXECUTE stmt_picker_name;
DEALLOCATE PREPARE stmt_picker_name;

SET @col_device_id := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_outbound_order'
       AND COLUMN_NAME = 'device_id'
);
SET @ddl_device_id := IF(@col_device_id = 0,
    'ALTER TABLE `wms_outbound_order` ADD COLUMN `device_id` varchar(36) DEFAULT NULL COMMENT ''领料设备ID（wms_device.id）''',
    'SELECT 1');
PREPARE stmt_device_id FROM @ddl_device_id;
EXECUTE stmt_device_id;
DEALLOCATE PREPARE stmt_device_id;

SET @col_cancel_reason := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_outbound_order'
       AND COLUMN_NAME = 'cancel_reason'
);
SET @ddl_cancel_reason := IF(@col_cancel_reason = 0,
    'ALTER TABLE `wms_outbound_order` ADD COLUMN `cancel_reason` varchar(500) DEFAULT NULL COMMENT ''取消原因''',
    'SELECT 1');
PREPARE stmt_cancel_reason FROM @ddl_cancel_reason;
EXECUTE stmt_cancel_reason;
DEALLOCATE PREPARE stmt_cancel_reason;

-- ------------------------------------------------ 6) 改制单
CREATE TABLE IF NOT EXISTS `wms_conversion_order` (
  `id`                    varchar(36)    NOT NULL COMMENT '主键',
  `conversion_no`         varchar(64)    NOT NULL COMMENT '改制单号（GZ 前缀）',
  `production_order_no`   varchar(64)    NOT NULL COMMENT '关联生产订单号（wms_work_order.order_no）',
  `input_material_code`   varchar(64)    NOT NULL COMMENT '投入物料编码',
  `input_batch_no`        varchar(64)    NOT NULL DEFAULT '' COMMENT '投入批次号',
  `input_qty`             decimal(20,6)  NOT NULL COMMENT '投入数量',
  `output_material_code`  varchar(64)    NOT NULL COMMENT '产出物料编码',
  `output_batch_no`       varchar(64)    NOT NULL DEFAULT '' COMMENT '产出批次号',
  `output_qty`            decimal(20,6)  NOT NULL COMMENT '产出计划数量',
  `status`                varchar(16)    NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT 草稿 / RUNNING 执行中 / DONE 已完成 / CANCELLED 已取消',
  `operator`              varchar(64)    DEFAULT NULL COMMENT '操作人',
  `erp_bill_no`           varchar(64)    DEFAULT NULL COMMENT '回写的金蝶单据号',
  `create_by`             varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`           datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`             varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`           datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_conversion_no` (`conversion_no`) USING BTREE,
  INDEX `idx_wms_conversion_po` (`production_order_no`) USING BTREE,
  INDEX `idx_wms_conversion_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='改制单（大改小）';

-- ------------------------------------------------ 7) 改制单明细
CREATE TABLE IF NOT EXISTS `wms_conversion_detail` (
  `id`             varchar(36)    NOT NULL COMMENT '主键',
  `conversion_id`  varchar(36)    NOT NULL COMMENT '改制单ID（wms_conversion_order.id）',
  `direction`      varchar(16)    NOT NULL COMMENT '方向：IN 投入 / OUT 产出',
  `material_code`  varchar(64)    NOT NULL COMMENT '物料编码',
  `batch_no`       varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号',
  `qty`            decimal(20,6)  NOT NULL COMMENT '数量',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_conversion_detail` (`conversion_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='改制单明细';

-- ------------------------------------------------ 8) 不良品记录（RK-005 两类来源）
CREATE TABLE IF NOT EXISTS `wms_defect_record` (
  `id`              varchar(36)    NOT NULL COMMENT '主键',
  `source_type`     varchar(16)    NOT NULL COMMENT '来源类型：PRODUCTION 生产 / CONSIGNED 受托',
  `source_bill_no`  varchar(64)    NOT NULL COMMENT '来源单号（生产入库单号 / 受托到货单号）',
  `material_code`   varchar(64)    NOT NULL COMMENT '物料编码',
  `batch_no`        varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号',
  `qty`             decimal(20,6)  NOT NULL COMMENT '不良数量',
  `reason`          varchar(500)   DEFAULT NULL COMMENT '不良原因',
  `disposal_type`   varchar(16)    DEFAULT NULL COMMENT '处置方式：SCRAP 入废品仓 / RETURN 退货',
  `return_bill_no`  varchar(64)    DEFAULT NULL COMMENT '采购退货单号',
  `status`          varchar(16)    NOT NULL DEFAULT '待处理' COMMENT '状态：待处理 / 已处置 / 已退货',
  `create_by`       varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`     datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_defect_source` (`source_type`, `source_bill_no`) USING BTREE,
  INDEX `idx_wms_defect_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='不良品记录';

-- ------------------------------------------------ 9) 恢复「供应商」入口
-- 受托方复用供应商主数据：只把 V3.9.5_16 收回的菜单与按钮改回未删除，不新建菜单。
UPDATE `sys_permission`
   SET `del_flag` = 0, `update_time` = NOW()
 WHERE `url` = '/wms/supplier'
   AND `del_flag` = 1;

UPDATE `sys_permission`
   SET `del_flag` = 0, `update_time` = NOW()
 WHERE `perms` LIKE 'wms:supplier:%'
   AND `del_flag` = 1;

-- ------------------------------------------------ 10) 新增菜单
-- 入库管理（1910000000000000101）下新增：外购/受托原材料入库、改制单
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000971','1910000000000000101','外购/受托原材料入库','/wms/inbound/raw','wms/inbound/raw/index',1,'','',1,NULL,'0',5.00,0,'ant-design:import-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000973','1910000000000000101','改制单','/wms/inbound/conversion','wms/inbound/conversion/index',1,'','',1,NULL,'0',6.00,0,'ant-design:swap-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 11) 新增按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000972','1910000000000000971','原材料入库-到货登记',NULL,NULL,0,NULL,'',2,'wms:inbound:consigned','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000974','1910000000000000973','改制单-查询',NULL,NULL,0,NULL,'',2,'wms:conversion:view','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000975','1910000000000000973','改制单-建单',NULL,NULL,0,NULL,'',2,'wms:conversion:create','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000976','1910000000000000973','改制单-完成',NULL,NULL,0,NULL,'',2,'wms:conversion:execute','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000977','1910000000000000973','改制单-取消',NULL,NULL,0,NULL,'',2,'wms:conversion:cancel','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000978','1910000000000000902','不良品-入库登记',NULL,NULL,0,NULL,'',2,'wms:defect:inbound','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000979','1910000000000000302','出库-取消领料',NULL,NULL,0,NULL,'',2,'wms:outbound:pick:cancel','1',99.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 12) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000971','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000971',NULL,NOW(),'127.0.0.1'),
('1920000000000000972','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000972',NULL,NOW(),'127.0.0.1'),
('1920000000000000973','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000973',NULL,NOW(),'127.0.0.1'),
('1920000000000000974','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000974',NULL,NOW(),'127.0.0.1'),
('1920000000000000975','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000975',NULL,NOW(),'127.0.0.1'),
('1920000000000000976','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000976',NULL,NOW(),'127.0.0.1'),
('1920000000000000977','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000977',NULL,NOW(),'127.0.0.1'),
('1920000000000000978','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000978',NULL,NOW(),'127.0.0.1'),
('1920000000000000979','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000979',NULL,NOW(),'127.0.0.1');
