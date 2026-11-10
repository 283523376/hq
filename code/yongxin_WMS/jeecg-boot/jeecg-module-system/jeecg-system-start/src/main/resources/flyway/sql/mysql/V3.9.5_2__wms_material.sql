-- ============================================================================
-- 永鑫WMS · 物料与分类管理（蓝图流程 YX-WMS-BD-001）
-- 内容：wms_material_category / wms_material 建表 + 菜单权限 + admin 授权
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
-- ============================================================================

-- ---------------------------------------------------------------- 建表 DDL
CREATE TABLE IF NOT EXISTS `wms_material_category` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `category_code` varchar(64)  NOT NULL COMMENT '分类编码',
  `category_name` varchar(100) NOT NULL COMMENT '分类名称',
  `parent_id`     varchar(32)  NOT NULL DEFAULT '0' COMMENT '父级ID，根节点为0',
  `category_type` varchar(20)  DEFAULT NULL COMMENT '分类类型：半成品/成品/辅料',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `has_child`     varchar(1)   DEFAULT '0' COMMENT '是否有子节点：1是 0否',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_mat_cat_code` (`category_code`) USING BTREE,
  INDEX `idx_wms_mat_cat_parent` (`parent_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='物料分类';

CREATE TABLE IF NOT EXISTS `wms_material` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `material_code` varchar(64)  NOT NULL COMMENT '物料编码',
  `material_name` varchar(100) NOT NULL COMMENT '物料名称',
  `category_id`   varchar(32)  DEFAULT NULL COMMENT '分类ID（wms_material_category.id）',
  `spec`          varchar(200) DEFAULT NULL COMMENT '规格',
  `unit`          varchar(20)  DEFAULT NULL COMMENT '单位',
  `shelf_life`    date         DEFAULT NULL COMMENT '保质期（到期日）',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_material_code` (`material_code`) USING BTREE,
  INDEX `idx_wms_material_category` (`category_id`) USING BTREE,
  INDEX `idx_wms_material_name` (`material_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='物料主数据';

-- ---------------------------------------------------------------- 菜单权限
-- 目录（menu_type=0）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000001','','物料管理','/wms','layouts/default/index',1,'','',0,NULL,'0',90.00,0,'ant-design:database-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 菜单（menu_type=1）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000002','1910000000000000001','物料与分类','/wms/material','wms/material/index',1,'','',1,NULL,'0',1.00,0,'ant-design:appstore-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000011','1910000000000000002','物料-新增',NULL,NULL,0,NULL,'',2,'wms:material:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000012','1910000000000000002','物料-编辑',NULL,NULL,0,NULL,'',2,'wms:material:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000013','1910000000000000002','物料-删除',NULL,NULL,0,NULL,'',2,'wms:material:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000014','1910000000000000002','物料-导入',NULL,NULL,0,NULL,'',2,'wms:material:import','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000015','1910000000000000002','物料-导出',NULL,NULL,0,NULL,'',2,'wms:material:export','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000016','1910000000000000002','分类-新增',NULL,NULL,0,NULL,'',2,'wms:materialCategory:add','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000017','1910000000000000002','分类-编辑',NULL,NULL,0,NULL,'',2,'wms:materialCategory:edit','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000018','1910000000000000002','分类-删除',NULL,NULL,0,NULL,'',2,'wms:materialCategory:delete','1',8.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000001','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000001',NULL,NOW(),'127.0.0.1'),
('1920000000000000002','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000002',NULL,NOW(),'127.0.0.1'),
('1920000000000000011','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000011',NULL,NOW(),'127.0.0.1'),
('1920000000000000012','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000012',NULL,NOW(),'127.0.0.1'),
('1920000000000000013','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000013',NULL,NOW(),'127.0.0.1'),
('1920000000000000014','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000014',NULL,NOW(),'127.0.0.1'),
('1920000000000000015','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000015',NULL,NOW(),'127.0.0.1'),
('1920000000000000016','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000016',NULL,NOW(),'127.0.0.1'),
('1920000000000000017','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000017',NULL,NOW(),'127.0.0.1'),
('1920000000000000018','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000018',NULL,NOW(),'127.0.0.1');
