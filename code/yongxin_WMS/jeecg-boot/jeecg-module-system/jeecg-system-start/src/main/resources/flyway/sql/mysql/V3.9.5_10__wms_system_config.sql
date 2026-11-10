-- ============================================================================
-- 永鑫WMS · 权限与系统配置（蓝图流程 YX-WMS-BD-004）
-- 内容：wms_system_config（配置值）+ wms_system_config_history（版本留痕）
--       + 「系统配置」菜单与保存/回滚按钮权限 + admin 授权
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
-- 注意：删除语义沿用全模块统一方案（deleted_at 时间戳），故唯一索引把 deleted_at 一起纳入 ——
--       单列唯一索引配 @TableLogic 会让「删除后重建」撞索引，是 V3.9.5_8/_9 已修掉的缺陷。
-- ============================================================================

-- ---------------------------------------------------------------- 配置值
CREATE TABLE IF NOT EXISTS `wms_system_config` (
  `id`           varchar(32)  NOT NULL COMMENT '主键',
  `config_key`   varchar(100) NOT NULL COMMENT '配置键',
  `config_value` varchar(500) DEFAULT NULL COMMENT '配置值（统一按字符串存，按元数据类型解析）',
  `config_desc`  varchar(200) DEFAULT NULL COMMENT '配置说明',
  `deleted_at`   bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`    varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`  datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`    varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`  datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_system_config_key` (`config_key`, `deleted_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统配置';

-- ---------------------------------------------------------------- 版本历史（只追加，永不删除）
-- 带 update_by/update_time：实体继承 JeecgEntity（含这两列），而 JeecgEntity 是
-- WmsUtils.fillCreate 的类型约束（T extends JeecgEntity）——不建这两列会让插入报「未知列」。
CREATE TABLE IF NOT EXISTS `wms_system_config_history` (
  `id`           varchar(32)  NOT NULL COMMENT '主键',
  `config_key`   varchar(100) NOT NULL COMMENT '配置键',
  `config_value` varchar(500) DEFAULT NULL COMMENT '该版本的配置值',
  `version_no`   int          NOT NULL COMMENT '该配置键的版本号，从 1 递增',
  `change_type`  varchar(20)  NOT NULL COMMENT '变更类型：SAVE=保存 ROLLBACK=回滚',
  `create_by`    varchar(50)  DEFAULT NULL COMMENT '操作人',
  `create_time`  datetime     DEFAULT NULL COMMENT '操作时间',
  `update_by`    varchar(50)  DEFAULT NULL COMMENT '更新人（本表只追加，此列恒为空，仅为与 JeecgEntity 对齐）',
  `update_time`  datetime     DEFAULT NULL COMMENT '更新时间（同上）',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_cfg_hist_key` (`config_key`, `version_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='系统配置版本历史';

-- ---------------------------------------------------------------- 菜单与按钮权限
-- 菜单（menu_type=1），父节点 = 1910000000000000001（基础管理）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000081','1910000000000000001','系统配置','/wms/systemConfig','wms/systemConfig/index',1,'','',1,NULL,'0',6.00,0,'ant-design:setting-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000082','1910000000000000081','配置-保存',NULL,NULL,0,NULL,'',2,'wms:systemConfig:save','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000083','1910000000000000081','配置-回滚',NULL,NULL,0,NULL,'',2,'wms:systemConfig:rollback','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- admin 授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000081','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000081',NULL,NOW(),'127.0.0.1'),
('1920000000000000082','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000082',NULL,NOW(),'127.0.0.1'),
('1920000000000000083','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000083',NULL,NOW(),'127.0.0.1');
