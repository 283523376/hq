-- ================================================================
-- XT-001 不良品处理出口：菜单 + 权限码
-- 设计：docs/superpowers/specs/2026-11-02-yx-wms-xt-001-不良品处理出口-design.md
-- 说明：报废出库复用 wms_outbound_order（类型=报废出库），无需建表；
--       本次只加菜单与权限，代码见 WmsDefectController / WmsDefectServiceImpl。
-- ================================================================

-- 1) 菜单：不良品处置（顶级，与「审计留痕」同级）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000901','','不良品处置','/wms/defect','layouts/default/index',1,'','/wms/defect/list',0,NULL,'0',97.00,0,'ant-design:delete-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000902','1910000000000000901','不良品台账','/wms/defect/list','wms/defect/index',1,'','',1,NULL,'0',1.00,0,'ant-design:unordered-list-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 2) 按钮权限：查询 / 报废
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000911','1910000000000000902','不良品-查询',NULL,NULL,0,NULL,'',2,'wms:defect:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000912','1910000000000000902','不良品-报废',NULL,NULL,0,NULL,'',2,'wms:defect:scrap','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 3) admin 角色授权（仓管/审计员角色由运维另行分配：审计员只授 :list）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000901','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000901',NULL,NOW(),'127.0.0.1'),
('1920000000000000902','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000902',NULL,NOW(),'127.0.0.1'),
('1920000000000000911','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000911',NULL,NOW(),'127.0.0.1'),
('1920000000000000912','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000912',NULL,NOW(),'127.0.0.1');
