-- ============================================================================
-- 永鑫WMS · 供应商与生产订单管理（蓝图流程 YX-WMS-BD-002）—— 菜单与任务种子
-- 内容：既有 /wms 目录改名为「基础管理」+ 两个新菜单 + 按钮权限 + admin 授权
--       + 补偿定时任务的 sys_quartz_job 种子行
-- 说明：本脚本可重复执行（INSERT IGNORE / UPDATE 带条件）
-- ============================================================================

-- ------------------------------------------------ 既有目录改名：物料管理 → 基础管理
-- 带 name='物料管理' 条件，重复执行不会覆盖后续的人工改名
UPDATE `sys_permission` SET `name` = '基础管理'
 WHERE `id` = '1910000000000000001' AND `name` = '物料管理';

-- ------------------------------------------------ 菜单（menu_type=1）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000021','1910000000000000001','供应商管理','/wms/supplier','wms/supplier/index',1,'','',1,NULL,'0',2.00,0,'ant-design:shop-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000022','1910000000000000001','订单管理','/wms/order','wms/order/index',1,'','',1,NULL,'0',3.00,0,'ant-design:profile-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000031','1910000000000000021','供应商-新增',NULL,NULL,0,NULL,'',2,'wms:supplier:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000032','1910000000000000021','供应商-编辑',NULL,NULL,0,NULL,'',2,'wms:supplier:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000033','1910000000000000021','供应商-删除',NULL,NULL,0,NULL,'',2,'wms:supplier:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000041','1910000000000000022','订单-新增',NULL,NULL,0,NULL,'',2,'wms:order:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000042','1910000000000000022','订单-编辑',NULL,NULL,0,NULL,'',2,'wms:order:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000043','1910000000000000022','订单-删除',NULL,NULL,0,NULL,'',2,'wms:order:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000044','1910000000000000022','订单-审核',NULL,NULL,0,NULL,'',2,'wms:order:audit','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000045','1910000000000000022','订单-下达',NULL,NULL,0,NULL,'',2,'wms:order:dispatch','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000046','1910000000000000022','订单-重新同步',NULL,NULL,0,NULL,'',2,'wms:order:resync','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ admin 角色授权
-- 注意：既有 admin 角色 id 取自 V3.9.5_2__wms_material.sql
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000021','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000021',NULL,NOW(),'127.0.0.1'),
('1920000000000000022','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000022',NULL,NOW(),'127.0.0.1'),
('1920000000000000031','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000031',NULL,NOW(),'127.0.0.1'),
('1920000000000000032','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000032',NULL,NOW(),'127.0.0.1'),
('1920000000000000033','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000033',NULL,NOW(),'127.0.0.1'),
('1920000000000000041','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000041',NULL,NOW(),'127.0.0.1'),
('1920000000000000042','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000042',NULL,NOW(),'127.0.0.1'),
('1920000000000000043','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000043',NULL,NOW(),'127.0.0.1'),
('1920000000000000044','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000044',NULL,NOW(),'127.0.0.1'),
('1920000000000000045','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000045',NULL,NOW(),'127.0.0.1'),
('1920000000000000046','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000046',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 补偿定时任务种子行
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」（适合验收演示）。
-- 实际调度由 WmsOrderSyncJobRegistrar 负责——本行不会被平台自动加载。
-- id 必须与 WmsConstants.SYNC_JOB_ID 同值；job_class_name 必须与 SYNC_JOB_CLASS 同值。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000101','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsOrderSyncRetryJob','0 3/5 * * * ? *',NULL,'WMS订单同步失败补偿任务',0);
