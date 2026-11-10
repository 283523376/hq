-- =============================================================
-- DY-001 单据打印：菜单与权限（修正 V3.9.5_52 的 id 冲突）
-- 2026-11-09
--
-- 背景：V3.9.5_52 用了 1910000000000000801/802/811 这一 id 段，但该段已被
-- XT-002「操作留痕」菜单占用（url=/wms/audit），INSERT IGNORE 静默跳过，
-- 导致菜单与 wms:print:list 权限都没落库（运行时表现为「没有权限，请联系管理员
-- 分配权限！」）。本脚本用确认空闲的 19100000000000010xx 段重新插入。
-- 52 已执行且 checksum 固定，不改它——修正一律走新脚本（Flyway 纪律）。
-- =============================================================

-- 注意：不要清理 801/802/811。本环境它们是「操作留痕」既有菜单，删除会毁掉 XT-002；
-- 其他环境若 52 恰好插入了打印菜单，多这一组重复菜单也不影响使用（前端可见即可）。

-- ------------------------------------------------ 1) 菜单：单据打印（一级 + 工作台页）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000001001','','单据打印','/wms/print','layouts/default/index',1,'','/wms/print/workbench',0,NULL,'0',88.00,0,'ant-design:printer-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000001002','1910000000000001001','打印工作台','/wms/print/workbench','wms/print/index',1,'','',1,NULL,'0',1.00,0,'ant-design:printer-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 2) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000001011','1910000000000001002','打印-查询',NULL,NULL,0,NULL,'',2,'wms:print:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 3) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000001001','f6817f48af4fb3af11b9e8bf182f618b','1910000000000001001',NULL,NOW(),'127.0.0.1'),
('1920000000000001002','f6817f48af4fb3af11b9e8bf182f618b','1910000000000001002',NULL,NOW(),'127.0.0.1'),
('1920000000000001011','f6817f48af4fb3af11b9e8bf182f618b','1910000000000001011',NULL,NOW(),'127.0.0.1');
