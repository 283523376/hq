-- ================================================================
-- KC-004 库存查询与报表导出：导出任务表 + 库存批次索引 + 菜单权限
-- 设计：docs/superpowers/specs/2026-10-29-yx-wms-kc-004-inventory-query-export-design.md
-- ================================================================

-- ------------------------------------------------ 1) 导出任务表（通用，非库存专用）
-- 不设 deleted_at：任务是短生命周期的工作产物，7 天后连记录带文件物理清理。
CREATE TABLE IF NOT EXISTS `wms_export_task` (
  `id`            varchar(32)   NOT NULL COMMENT '主键',
  `task_type`     varchar(32)   NOT NULL COMMENT '任务类型：INVENTORY_LEDGER 库存台账',
  `query_params`  text          NOT NULL COMMENT '查询条件 JSON 快照（含格式版本）',
  `status`        varchar(16)   NOT NULL COMMENT '状态：PENDING 排队 / RUNNING 生成中 / SUCCESS 成功 / FAILED 失败',
  `file_name`     varchar(255)  DEFAULT NULL COMMENT '下载文件名',
  `file_path`     varchar(500)  DEFAULT NULL COMMENT '相对导出根目录的路径',
  `file_size`     bigint        DEFAULT NULL COMMENT '文件字节数',
  `row_count`     bigint        DEFAULT NULL COMMENT '导出数据行数',
  `snapshot_time` datetime      DEFAULT NULL COMMENT '一致性快照时点（事务开始时间）',
  `finish_time`   datetime      DEFAULT NULL COMMENT '完成时间',
  `expire_time`   datetime      DEFAULT NULL COMMENT '清理时点：任何终态完成时写 finish_time+7天',
  `error_msg`     varchar(1000) DEFAULT NULL COMMENT '失败原因（截断）',
  `create_by`     varchar(50)   DEFAULT NULL COMMENT '导出人账号',
  `create_time`   datetime      DEFAULT NULL COMMENT '提交时间',
  `update_by`     varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_export_owner` (`create_by`, `create_time`) USING BTREE,
  INDEX `idx_wms_export_status` (`status`) USING BTREE,
  INDEX `idx_wms_export_expire` (`expire_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='导出任务';

-- ------------------------------------------------ 2) wms_inventory 补批次号索引
-- 唯一键 (material_id, location_id, batch_no, container_code) 的第二列是 location_id，
-- 单独按 batch_no 过滤用不上；台账的批次检索要它。
SET @idx_inv_batch := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_inventory'
       AND INDEX_NAME = 'idx_wms_inv_batch'
);
SET @ddl_inv_batch := IF(@idx_inv_batch = 0,
    'ALTER TABLE `wms_inventory` ADD INDEX `idx_wms_inv_batch` (`batch_no`) USING BTREE',
    'SELECT 1');
PREPARE stmt_inv_batch FROM @ddl_inv_batch;
EXECUTE stmt_inv_batch;
DEALLOCATE PREPARE stmt_inv_batch;

-- ------------------------------------------------ 3) 菜单：库存查询（sort=97）+ 库存台账
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000701','','库存查询','/wms/inventoryLedger','layouts/default/index',1,'','/wms/inventoryLedger/list',0,NULL,'0',97.00,0,'ant-design:profile-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000702','1910000000000000701','库存台账','/wms/inventoryLedger/list','wms/inventoryLedger/index',1,'','',1,NULL,'0',1.00,0,'ant-design:table-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 4) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000711','1910000000000000702','库存-查询',NULL,NULL,0,NULL,'',2,'wms:inventory:ledger','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000712','1910000000000000702','库存-导出',NULL,NULL,0,NULL,'',2,'wms:inventory:export','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 5) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000701','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000701',NULL,NOW(),'127.0.0.1'),
('1920000000000000702','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000702',NULL,NOW(),'127.0.0.1'),
('1920000000000000711','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000711',NULL,NOW(),'127.0.0.1'),
('1920000000000000712','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000712',NULL,NOW(),'127.0.0.1');
