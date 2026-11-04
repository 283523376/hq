-- ================================================================
-- V2.4 Task 9 补齐：不良品退货权限码
-- 背景：V3.9.5_47 已插入「不良品-入库登记」(wms:defect:inbound)，
--       但 spec §5 的 POST /wms/defect/return 没有对应权限码；
--       Controller 若声明 wms:defect:return 而权限表没有该码，运行时必 403。
-- 说明：另起脚本而非回改 V3.9.5_47——47 在其他环境可能已执行，回改会 checksum 失败
--       且被 FlywayConfig 静默吞掉（本机实测 47 尚未执行，但仍按「已发布脚本不回改」处理）。
--       只新增权限与 admin 授权，不改表结构、不改既有脚本。
-- ================================================================

INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000980','1910000000000000902','不良品-退货',NULL,NULL,0,NULL,'',2,'wms:defect:return','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- admin 角色授权（仓管/审计员角色由运维另行分配）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000980','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000980',NULL,NOW(),'127.0.0.1');
