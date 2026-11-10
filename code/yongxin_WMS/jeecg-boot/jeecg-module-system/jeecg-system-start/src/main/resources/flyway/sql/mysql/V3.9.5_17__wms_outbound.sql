-- ============================================================================
-- 永鑫WMS · CK-001（半成品领料出库）/ CK-002（成品销售出库）
-- 内容：
--   1) 新建 wms_outbound_order / wms_outbound_detail / wms_outbound_allocation
--   2) wms_inventory 增加 container_code、locked_qty，唯一键纳入容器维度
--   3) 菜单「出库管理 / 出库单」+ 按钮权限 + admin 授权
--   4) 出库同步补偿任务种子行
-- 说明：脚本可重复执行（IF NOT EXISTS / information_schema 判存在 / INSERT IGNORE）
-- ============================================================================

-- ------------------------------------------------ 1) 出库单
CREATE TABLE IF NOT EXISTS `wms_outbound_order` (
  `id`                varchar(32)   NOT NULL COMMENT '主键',
  `outbound_no`       varchar(64)   NOT NULL COMMENT '出库单号：LL/S 前缀',
  `outbound_type`     varchar(16)   NOT NULL COMMENT '生产领料/销售出库',
  `work_order_no`     varchar(64)   DEFAULT NULL COMMENT '生产工单号（CK-001）',
  `sales_order_no`    varchar(64)   DEFAULT NULL COMMENT '销售订单号（CK-002）',
  `customer_code`     varchar(64)   DEFAULT NULL COMMENT '客户编码',
  `customer_name`     varchar(128)  DEFAULT NULL COMMENT '客户名称',
  `delivery_date`     date          DEFAULT NULL COMMENT '要货日期（回写金蝶销售订单 FDeliveryDate）',
  `requirement_source` varchar(16)  DEFAULT '手工' COMMENT '金蝶用料清单/手工',
  `status`            varchar(20)   NOT NULL DEFAULT '待分配' COMMENT '待分配/已分配/执行中/待复核/复核异常/异常待人工/已完成/已关闭',
  `sale_sync_status`  varchar(20)   DEFAULT '无需同步' COMMENT '销售订单同步状态',
  `sale_sync_error`   varchar(500)  DEFAULT NULL COMMENT '销售订单同步失败原因',
  `sale_sync_time`    datetime      DEFAULT NULL COMMENT '销售订单最近同步时间',
  `sync_status`       varchar(20)   NOT NULL DEFAULT '无需同步' COMMENT '出库单同步状态',
  `retry_count`       int(11)       NOT NULL DEFAULT 0 COMMENT '最近一次同步重试次数',
  `last_sync_error`   varchar(500)  DEFAULT NULL COMMENT '最后同步失败原因',
  `last_sync_time`    datetime      DEFAULT NULL COMMENT '最后同步时间',
  `sync_fail_count`   int(11)       NOT NULL DEFAULT 0 COMMENT '后台同步累计失败次数',
  `override_by`       varchar(50)   DEFAULT NULL COMMENT '销售订单未同步时确认出库的操作人',
  `override_time`     datetime      DEFAULT NULL COMMENT '确认出库时间',
  `override_reason`   varchar(500)  DEFAULT NULL COMMENT '确认出库原因',
  `close_reason`      varchar(500)  DEFAULT NULL COMMENT '关单原因',
  `close_by`          varchar(50)   DEFAULT NULL COMMENT '关单人',
  `close_time`        datetime      DEFAULT NULL COMMENT '关单时间',
  `operator`          varchar(50)   DEFAULT NULL COMMENT '创建人',
  `submit_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `complete_time`     datetime      DEFAULT NULL COMMENT '完成时间',
  `remark`            varchar(500)  DEFAULT NULL COMMENT '备注',
  `deleted_at`        bigint        NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除',
  `create_by`         varchar(50)   DEFAULT NULL COMMENT '创建人',
  `create_time`       datetime      DEFAULT NULL COMMENT '创建时间',
  `update_by`         varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`       datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_outbound_no` (`outbound_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_outbound_status` (`status`) USING BTREE,
  INDEX `idx_wms_outbound_type` (`outbound_type`) USING BTREE,
  INDEX `idx_wms_outbound_sale_sync` (`sale_sync_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='出库单';

-- ------------------------------------------------ 2) 出库需求明细
CREATE TABLE IF NOT EXISTS `wms_outbound_detail` (
  `id`            varchar(32)   NOT NULL COMMENT '主键',
  `outbound_id`   varchar(32)   NOT NULL COMMENT '出库单ID',
  `line_no`       int(11)       NOT NULL COMMENT '行号',
  `material_id`   varchar(32)   NOT NULL COMMENT '物料ID（wms_material.id）',
  `material_code` varchar(64)   NOT NULL COMMENT '物料编码',
  `material_name` varchar(100)  DEFAULT NULL COMMENT '物料名称',
  `batch_no`      varchar(64)   DEFAULT NULL COMMENT '批次号',
  `required_qty`  decimal(12,3) NOT NULL COMMENT '需求数量',
  `allocated_qty` decimal(12,3) NOT NULL DEFAULT 0 COMMENT '已分配数量',
  `actual_qty`    decimal(12,3) NOT NULL DEFAULT 0 COMMENT '实发/复核确认数量',
  `unit`          varchar(16)   DEFAULT NULL COMMENT '单位',
  `source_entry_id` varchar(64) DEFAULT NULL COMMENT '金蝶 PPBOM 子项来源内码',
  `deleted_at`    bigint        NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除',
  `create_by`     varchar(50)   DEFAULT NULL,
  `create_time`   datetime      DEFAULT NULL,
  `update_by`     varchar(50)   DEFAULT NULL,
  `update_time`   datetime      DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_out_detail_out` (`outbound_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='出库需求明细';

-- ------------------------------------------------ 3) 出库分配明细
CREATE TABLE IF NOT EXISTS `wms_outbound_allocation` (
  `id`            varchar(32)   NOT NULL COMMENT '主键',
  `outbound_id`   varchar(32)   NOT NULL COMMENT '出库单ID',
  `detail_id`     varchar(32)   NOT NULL COMMENT '需求明细ID',
  `inventory_id`  varchar(32)   NOT NULL COMMENT '库存行ID',
  `location_id`   varchar(32)   DEFAULT NULL COMMENT '库位ID',
  `location_code` varchar(64)   DEFAULT NULL COMMENT '库位编码快照',
  `container_code` varchar(64)  NOT NULL DEFAULT '' COMMENT '料箱/托盘编码（敏捷仓）',
  `allocated_qty` decimal(12,3) NOT NULL COMMENT '分配数量',
  `actual_qty`    decimal(12,3) NOT NULL DEFAULT 0 COMMENT '复核确认数量',
  `task_no`       varchar(64)   DEFAULT NULL COMMENT 'WCS/RMS 任务号',
  `task_status`   varchar(20)   NOT NULL DEFAULT '未下发' COMMENT '未下发/已受理/执行中/已完成/失败/已关闭',
  `task_error`    varchar(500)  DEFAULT NULL COMMENT '任务失败原因',
  `review_status` varchar(20)   DEFAULT '待复核' COMMENT '待复核/一致/不一致/已放行',
  `scan_code`     varchar(64)   DEFAULT NULL COMMENT '复核扫描码',
  `review_note`   varchar(500)  DEFAULT NULL COMMENT '复核说明/放行原因',
  `deleted_at`    bigint        NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除',
  `create_by`     varchar(50)   DEFAULT NULL,
  `create_time`   datetime      DEFAULT NULL,
  `update_by`     varchar(50)   DEFAULT NULL,
  `update_time`   datetime      DEFAULT NULL,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_out_alloc_out` (`outbound_id`) USING BTREE,
  INDEX `idx_wms_out_alloc_task` (`task_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='出库分配明细';

-- ------------------------------------------------ 4) 库存增加容器与锁定维度
SET @col_container := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND COLUMN_NAME = 'container_code'
);
SET @ddl := IF(@col_container = 0,
    'ALTER TABLE `wms_inventory` ADD COLUMN `container_code` varchar(64) NOT NULL DEFAULT '''' COMMENT ''料箱/托盘编码（敏捷仓按箱记库存；立体库为空串）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_locked := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND COLUMN_NAME = 'locked_qty'
);
SET @ddl_locked := IF(@col_locked = 0,
    'ALTER TABLE `wms_inventory` ADD COLUMN `locked_qty` decimal(12,3) NOT NULL DEFAULT 0 COMMENT ''出库分配锁定数量''',
    'SELECT 1');
PREPARE stmt_locked FROM @ddl_locked;
EXECUTE stmt_locked;
DEALLOCATE PREPARE stmt_locked;

-- 唯一键从 (material, location, batch) 扩展为 (material, location, batch, container)
SET @old_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'uk_wms_inv_mat_loc_batch'
);
SET @ddl_drop := IF(@old_idx > 0,
    'ALTER TABLE `wms_inventory` DROP INDEX `uk_wms_inv_mat_loc_batch`',
    'SELECT 1');
PREPARE stmt_drop FROM @ddl_drop;
EXECUTE stmt_drop;
DEALLOCATE PREPARE stmt_drop;

SET @new_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'uk_wms_inv_mat_loc_batch_container'
);
SET @ddl_new_idx := IF(@new_idx = 0,
    'ALTER TABLE `wms_inventory` ADD UNIQUE INDEX `uk_wms_inv_mat_loc_batch_container` (`material_id`, `location_id`, `batch_no`, `container_code`)',
    'SELECT 1');
PREPARE stmt_new_idx FROM @ddl_new_idx;
EXECUTE stmt_new_idx;
DEALLOCATE PREPARE stmt_new_idx;

-- ------------------------------------------------ 5) 菜单：出库管理（顶层，sort=92）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000301','','出库管理','/wms/outbound','layouts/default/index',1,'','/wms/outbound/list',0,NULL,'0',92.00,0,'ant-design:export-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000302','1910000000000000301','出库单','/wms/outbound/list','wms/outbound/index',1,'','',1,NULL,'0',1.00,0,'ant-design:unordered-list-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 6) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000311','1910000000000000302','出库-查询',NULL,NULL,0,NULL,'',2,'wms:outbound:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000312','1910000000000000302','出库-扫码工单',NULL,NULL,0,NULL,'',2,'wms:outbound:scan','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000313','1910000000000000302','出库-新建领料',NULL,NULL,0,NULL,'',2,'wms:outbound:add','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000314','1910000000000000302','出库-新建销售',NULL,NULL,0,NULL,'',2,'wms:outbound:sales','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000315','1910000000000000302','出库-分配',NULL,NULL,0,NULL,'',2,'wms:outbound:allocate','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000316','1910000000000000302','出库-下发',NULL,NULL,0,NULL,'',2,'wms:outbound:dispatch','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000317','1910000000000000302','出库-复核',NULL,NULL,0,NULL,'',2,'wms:outbound:review','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000318','1910000000000000302','出库-确认',NULL,NULL,0,NULL,'',2,'wms:outbound:confirm','1',8.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000319','1910000000000000302','出库-同步',NULL,NULL,0,NULL,'',2,'wms:outbound:sync','1',9.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000320','1910000000000000302','出库-关单',NULL,NULL,0,NULL,'',2,'wms:outbound:close','1',10.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 7) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000301','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000301',NULL,NOW(),'127.0.0.1'),
('1920000000000000302','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000302',NULL,NOW(),'127.0.0.1'),
('1920000000000000311','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000311',NULL,NOW(),'127.0.0.1'),
('1920000000000000312','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000312',NULL,NOW(),'127.0.0.1'),
('1920000000000000313','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000313',NULL,NOW(),'127.0.0.1'),
('1920000000000000314','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000314',NULL,NOW(),'127.0.0.1'),
('1920000000000000315','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000315',NULL,NOW(),'127.0.0.1'),
('1920000000000000316','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000316',NULL,NOW(),'127.0.0.1'),
('1920000000000000317','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000317',NULL,NOW(),'127.0.0.1'),
('1920000000000000318','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000318',NULL,NOW(),'127.0.0.1'),
('1920000000000000319','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000319',NULL,NOW(),'127.0.0.1'),
('1920000000000000320','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000320',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 8) 出库同步补偿任务种子行
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」；实际调度由 Registrar 负责。
-- id 必须与 WmsConstants.OUTBOUND_SYNC_JOB_ID 同值；cron 与订单(3/5)、设备(7/5)、采购入库(9/5)、生产入库(13/5) 错峰。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000300','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsOutboundSyncRetryJob','0 17/5 * * * ? *',NULL,'WMS出库同步补偿任务',0);
