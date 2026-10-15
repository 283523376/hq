-- ============================================================================
-- 永鑫WMS · 库区/库位与设备绑定（蓝图流程 YX-WMS-BD-003）
-- 内容：wms_warehouse / wms_location / wms_device 建表 + 菜单权限 + 离线扫描任务种子
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE）
-- ============================================================================

-- ---------------------------------------------------------------- 建表 DDL
CREATE TABLE IF NOT EXISTS `wms_warehouse` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `warehouse_code` varchar(64) NOT NULL COMMENT '库区编码',
  `warehouse_name` varchar(100) NOT NULL COMMENT '库区名称',
  `warehouse_type` varchar(20)  NOT NULL COMMENT '库区类型：立库/敏捷仓/线边柜',
  `row_count`     int          DEFAULT NULL COMMENT '生成上限-排数（可自由增减，不参与编码计算）',
  `col_count`     int          DEFAULT NULL COMMENT '生成上限-列数（可自由增减，不参与编码计算）',
  `layer_count`   int          DEFAULT NULL COMMENT '生成上限-层数（可自由增减，不参与编码计算）',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_warehouse_code` (`warehouse_code`) USING BTREE,
  INDEX `idx_wms_warehouse_name` (`warehouse_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库区';

CREATE TABLE IF NOT EXISTS `wms_location` (
  `id`             varchar(32)  NOT NULL COMMENT '主键',
  `location_code`  varchar(64)  NOT NULL COMMENT '库位编码',
  `warehouse_id`   varchar(32)  NOT NULL COMMENT '库区ID',
  `row_no`         int          NOT NULL COMMENT '排',
  `col_no`         int          NOT NULL COMMENT '列',
  `layer_no`       int          NOT NULL COMMENT '层',
  `max_weight`     decimal(12,3) DEFAULT NULL COMMENT '承重',
  `status`         varchar(10)  NOT NULL DEFAULT '空闲' COMMENT '状态：空闲/占用/故障',
  `del_flag`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`      varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_location_code` (`location_code`) USING BTREE,
  UNIQUE INDEX `uk_wms_location_grid` (`warehouse_id`,`row_no`,`col_no`,`layer_no`) USING BTREE,
  INDEX `idx_wms_location_warehouse` (`warehouse_id`) USING BTREE,
  INDEX `idx_wms_location_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库位';

CREATE TABLE IF NOT EXISTS `wms_device` (
  `id`             varchar(32)  NOT NULL COMMENT '主键',
  `device_code`    varchar(64)  NOT NULL COMMENT '设备编码',
  `device_name`    varchar(100) NOT NULL COMMENT '设备名称',
  `device_type`    varchar(20)  NOT NULL COMMENT '设备类型：堆垛机/SCR/分拣单元/充电桩',
  `warehouse_id`   varchar(32)  DEFAULT NULL COMMENT '归属库区ID，NULL=未绑定',
  `status`         varchar(10)  NOT NULL DEFAULT '离线' COMMENT '状态：在线/离线/故障',
  `last_heartbeat` datetime     DEFAULT NULL COMMENT '最近一次心跳（或显式置在线）时刻',
  `last_alert_time` datetime    DEFAULT NULL COMMENT '最近一次离线告警时刻（节流用）',
  `del_flag`       tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`      varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_device_code` (`device_code`) USING BTREE,
  INDEX `idx_wms_device_warehouse` (`warehouse_id`) USING BTREE,
  INDEX `idx_wms_device_type_status` (`device_type`,`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='设备';

-- ---------------------------------------------------------------- 菜单权限
-- 菜单（menu_type=1），父节点 = 1910000000000000001（V3.9.5_4 已改名为「基础管理」）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000051','1910000000000000001','库区与库位','/wms/warehouse','wms/warehouse/index',1,'','',1,NULL,'0',4.00,0,'ant-design:apartment-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000052','1910000000000000001','设备管理','/wms/device','wms/device/index',1,'','',1,NULL,'0',5.00,0,'ant-design:deployment-unit-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000061','1910000000000000051','库区-新增',NULL,NULL,0,NULL,'',2,'wms:warehouse:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000062','1910000000000000051','库区-编辑',NULL,NULL,0,NULL,'',2,'wms:warehouse:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000063','1910000000000000051','库区-删除',NULL,NULL,0,NULL,'',2,'wms:warehouse:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000064','1910000000000000051','库位-生成',NULL,NULL,0,NULL,'',2,'wms:location:generate','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000065','1910000000000000051','库位-属性',NULL,NULL,0,NULL,'',2,'wms:location:attr','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000066','1910000000000000051','库位-释放',NULL,NULL,0,NULL,'',2,'wms:location:release','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000067','1910000000000000051','库位-删除',NULL,NULL,0,NULL,'',2,'wms:location:delete','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000068','1910000000000000052','设备-新增',NULL,NULL,0,NULL,'',2,'wms:device:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000069','1910000000000000052','设备-编辑',NULL,NULL,0,NULL,'',2,'wms:device:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000070','1910000000000000052','设备-删除',NULL,NULL,0,NULL,'',2,'wms:device:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000071','1910000000000000052','设备-绑定',NULL,NULL,0,NULL,'',2,'wms:device:bind','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000051','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000051',NULL,NOW(),'127.0.0.1'),
('1920000000000000052','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000052',NULL,NOW(),'127.0.0.1'),
('1920000000000000061','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000061',NULL,NOW(),'127.0.0.1'),
('1920000000000000062','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000062',NULL,NOW(),'127.0.0.1'),
('1920000000000000063','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000063',NULL,NOW(),'127.0.0.1'),
('1920000000000000064','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000064',NULL,NOW(),'127.0.0.1'),
('1920000000000000065','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000065',NULL,NOW(),'127.0.0.1'),
('1920000000000000066','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000066',NULL,NOW(),'127.0.0.1'),
('1920000000000000067','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000067',NULL,NOW(),'127.0.0.1'),
('1920000000000000068','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000068',NULL,NOW(),'127.0.0.1'),
('1920000000000000069','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000069',NULL,NOW(),'127.0.0.1'),
('1920000000000000070','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000070',NULL,NOW(),'127.0.0.1'),
('1920000000000000071','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000071',NULL,NOW(),'127.0.0.1');

-- ---------------------------------------------------------------- 离线扫描任务种子
-- 注意：本行只让任务在「定时任务管理」UI 可见（便于用「立即执行」手工触发）。
-- 真正把任务注册进调度器的是 WmsDeviceOfflineScanJobRegistrar（ApplicationReadyEvent 自注册），
-- 因为 sys_quartz_job 表只是注册表元数据，全库没有任何启动加载器。
-- 列顺序与类型对齐既有 V3.9.5_4 的订单补偿任务种子行（status 为整数 0 = 正常）。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000102','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsDeviceOfflineScanJob','0 7/5 * * * ? *',NULL,'WMS设备离线扫描告警任务',0);