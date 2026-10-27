-- ============================================================================
-- 补两个缺失的库位按钮权限：wms:location:add / wms:location:edit
--
-- 背景：前端 warehouse/index.vue 早就在用 wms:location:add 控制「新增库位」按钮，
-- 但 sys_permission 里从来没有这个码；后端 WmsLocationController 也一直没有
-- @RequiresPermissions。给后端补注解时必须先把码建出来，否则「注解一加、所有人 403」。
-- ============================================================================

INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000520','1910000000000000051','库位-新增',NULL,NULL,0,NULL,'',2,'wms:location:add','1',8.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000521','1910000000000000051','库位-编辑',NULL,NULL,0,NULL,'',2,'wms:location:edit','1',9.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 授权：以「库位-属性」(1910000000000000065) 为基准——凡是能改库位属性的角色，
-- 一并拿到新增/编辑，避免升级后库位维护对非 admin 角色直接不可用。
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`)
SELECT REPLACE(UUID(),'-',''), rp.role_id, '1910000000000000520', NULL, NOW(), '127.0.0.1'
FROM `sys_role_permission` rp WHERE rp.permission_id = '1910000000000000065';

INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`)
SELECT REPLACE(UUID(),'-',''), rp.role_id, '1910000000000000521', NULL, NOW(), '127.0.0.1'
FROM `sys_role_permission` rp WHERE rp.permission_id = '1910000000000000065';
