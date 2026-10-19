-- ============================================================================
-- 永鑫WMS · 生产入库流程（蓝图流程 YX-WMS-RK-002）
-- 内容：wms_work_order / wms_production_inbound 建表
--       + 废品仓与敏捷仓在库默认位种子
--       + 「生产工单」「生产入库」菜单、按钮权限、admin 授权
--       + 生产入库同步补偿任务种子行 + 验收工单种子
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE / 带条件 UPDATE）
-- 口径：载具即标识（成品料箱二维码 / 半成品周转盘 RFID），见
--       docs/superpowers/specs/2026-10-19-yx-wms-rk-002-production-inbound-design.md
-- ============================================================================

-- ---------------------------------------------------------------- 生产工单
CREATE TABLE IF NOT EXISTS `wms_work_order` (
  `id`                varchar(32)  NOT NULL COMMENT '主键',
  `work_order_no`     varchar(64)  NOT NULL COMMENT '工单号',
  `material_id`       varchar(32)  NOT NULL COMMENT '物料ID（wms_material.id）',
  `material_code`     varchar(64)  DEFAULT NULL COMMENT '物料编码（冗余）',
  `material_name`     varchar(100) DEFAULT NULL COMMENT '物料名称（冗余）',
  `product_type`      varchar(10)  NOT NULL COMMENT '产品类型：半成品/成品',
  `plan_qty`          decimal(12,3) NOT NULL COMMENT '计划数量',
  `batch_no`          varchar(64)  NOT NULL COMMENT '批次号',
  `work_order_status` varchar(20)  NOT NULL DEFAULT '生产中' COMMENT '工单状态：待生产/生产中/已完成/已关闭',
  `source`            varchar(20)  DEFAULT 'MES' COMMENT '来源：MES/手工',
  `mes_order_no`      varchar(64)  DEFAULT NULL COMMENT 'MES侧单号',
  `remark`            varchar(500) DEFAULT NULL COMMENT '备注',
  `deleted_at`        bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`         varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`       datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`         varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`       datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_work_order_no` (`work_order_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_work_order_material` (`material_id`) USING BTREE,
  INDEX `idx_wms_work_order_status` (`work_order_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='生产工单';

-- ---------------------------------------------------------------- 生产入库
CREATE TABLE IF NOT EXISTS `wms_production_inbound` (
  `id`                varchar(32)  NOT NULL COMMENT '主键',
  `inbound_no`        varchar(64)  NOT NULL COMMENT '入库单号（SC前缀）',
  `work_order_no`     varchar(64)  NOT NULL COMMENT '工单号',
  `product_type`      varchar(10)  NOT NULL COMMENT '产品类型：半成品/成品',
  `material_id`       varchar(32)  NOT NULL COMMENT '物料ID',
  `material_code`     varchar(64)  DEFAULT NULL COMMENT '物料编码（冗余）',
  `material_name`     varchar(100) DEFAULT NULL COMMENT '物料名称（冗余）',
  `batch_no`          varchar(64)  NOT NULL COMMENT '批次号',
  `quantity`          decimal(12,3) NOT NULL COMMENT '本次入库总量=good_qty+bad_qty',
  `good_qty`          decimal(12,3) NOT NULL DEFAULT 0 COMMENT '良品数量',
  `bad_qty`           decimal(12,3) NOT NULL DEFAULT 0 COMMENT '不良品数量',
  `status`            varchar(20)  NOT NULL DEFAULT '待上架' COMMENT '状态：待上架/执行中/已入库/异常待人工/已关闭',
  `carrier_code`      varchar(64)  NOT NULL COMMENT '载具码（成品料箱码/半成品周转盘RFID号）',
  `ident_type`        varchar(10)  NOT NULL COMMENT '标识类型：二维码/RFID',
  `location_id`       varchar(32)  DEFAULT NULL COMMENT '良品目标库位（半成品立库库位/成品敏捷仓在库默认位）',
  `scrap_location_id` varchar(32)  DEFAULT NULL COMMENT '不良品写入的废品仓库位',
  `stack_code`        varchar(64)  DEFAULT NULL COMMENT 'RMS回执的料箱库位编码',
  `wcs_task_no`       varchar(64)  DEFAULT NULL COMMENT '下发任务号（提交时生成并固定）',
  `task_status`       varchar(20)  NOT NULL DEFAULT '未下发' COMMENT '任务状态：未下发/已受理/执行中/已完成/失败/已关闭',
  `task_error`        varchar(500) DEFAULT NULL COMMENT '下发/回执失败原因',
  `defect_remark`     varchar(500) DEFAULT NULL COMMENT '不良原因',
  `close_reason`      varchar(500) DEFAULT NULL COMMENT '关单原因',
  `close_by`          varchar(50)  DEFAULT NULL COMMENT '关单人',
  `close_time`        datetime     DEFAULT NULL COMMENT '关单时间',
  `sync_status`       varchar(20)  NOT NULL DEFAULT '无需同步' COMMENT '同步状态：待同步/同步中/同步成功/同步失败/无需同步',
  `retry_count`       int(11)      NOT NULL DEFAULT 0 COMMENT '最近一次同步尝试中的重试次数',
  `last_sync_error`   varchar(500) DEFAULT NULL COMMENT '最后一次同步失败原因',
  `last_sync_time`    datetime     DEFAULT NULL COMMENT '最后一次同步尝试时间',
  `operator`          varchar(50)  DEFAULT NULL COMMENT '操作员',
  `submit_time`       datetime     DEFAULT NULL COMMENT '提交时间',
  `complete_time`     datetime     DEFAULT NULL COMMENT '入库完成时间',
  `deleted_at`        bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`         varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`       datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`         varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`       datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_production_inbound_no` (`inbound_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_prod_inbound_wo` (`work_order_no`) USING BTREE,
  INDEX `idx_wms_prod_inbound_carrier` (`carrier_code`) USING BTREE,
  INDEX `idx_wms_prod_inbound_status` (`status`) USING BTREE,
  INDEX `idx_wms_prod_inbound_task` (`task_status`) USING BTREE,
  INDEX `idx_wms_prod_inbound_sync` (`sync_status`, `retry_count`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='生产入库';

-- ------------------------------------------------ 废品仓与敏捷仓在库默认位
-- 废品仓：不良品不做库位分配，直接写 FPC-DEFAULT 的库存
INSERT IGNORE INTO `wms_warehouse` (`id`,`warehouse_code`,`warehouse_name`,`warehouse_type`,`row_count`,`col_count`,`layer_count`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907400','FPC01','废品仓','废品仓',1,1,1,'1',0,'admin',NOW());

INSERT IGNORE INTO `wms_location` (`id`,`location_code`,`warehouse_id`,`row_no`,`col_no`,`layer_no`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907401','FPC-DEFAULT','2110885981815907400',1,1,1,'占用',0,'admin',NOW());

-- 敏捷仓在库默认位：成品库存在 WMS 侧按物料+批次聚合，料箱实际位置由 RMS 管理
-- warehouse_id 取自运行库已确认的「成品敏捷仓」（warehouse_code=MJ）
INSERT IGNORE INTO `wms_location` (`id`,`location_code`,`warehouse_id`,`row_no`,`col_no`,`layer_no`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907402','MJ-DEFAULT','2110885982046593025',1,1,1,'占用',0,'admin',NOW());

-- ------------------------------------------------ 菜单（menu_type=1，挂在「入库管理」下）
-- 既有子菜单：采购入库 sort=1、检验任务 sort=2；本流程从 3 开始
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000201','1910000000000000101','生产工单','/wms/work-order','wms/workOrder/index',1,'','',1,NULL,'0',3.00,0,'ant-design:profile-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000202','1910000000000000101','生产入库','/wms/production/inbound','wms/production/index',1,'','',1,NULL,'0',4.00,0,'ant-design:build-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000211','1910000000000000201','工单-新增',NULL,NULL,0,NULL,'',2,'wms:work-order:add','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000212','1910000000000000201','工单-编辑',NULL,NULL,0,NULL,'',2,'wms:work-order:edit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000213','1910000000000000201','工单-删除',NULL,NULL,0,NULL,'',2,'wms:work-order:delete','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000221','1910000000000000202','生产入库-扫码',NULL,NULL,0,NULL,'',2,'wms:production:scan','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000222','1910000000000000202','生产入库-提交',NULL,NULL,0,NULL,'',2,'wms:production:submit','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000223','1910000000000000202','生产入库-重新下发',NULL,NULL,0,NULL,'',2,'wms:production:dispatch','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000224','1910000000000000202','生产入库-转人工完成',NULL,NULL,0,NULL,'',2,'wms:production:manual','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000225','1910000000000000202','生产入库-关单',NULL,NULL,0,NULL,'',2,'wms:production:close','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000226','1910000000000000202','生产入库-重新同步',NULL,NULL,0,NULL,'',2,'wms:production:sync','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ admin 角色授权（role_id 取自既有种子）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000201','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000201',NULL,NOW(),'127.0.0.1'),
('1920000000000000202','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000202',NULL,NOW(),'127.0.0.1'),
('1920000000000000211','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000211',NULL,NOW(),'127.0.0.1'),
('1920000000000000212','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000212',NULL,NOW(),'127.0.0.1'),
('1920000000000000213','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000213',NULL,NOW(),'127.0.0.1'),
('1920000000000000221','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000221',NULL,NOW(),'127.0.0.1'),
('1920000000000000222','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000222',NULL,NOW(),'127.0.0.1'),
('1920000000000000223','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000223',NULL,NOW(),'127.0.0.1'),
('1920000000000000224','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000224',NULL,NOW(),'127.0.0.1'),
('1920000000000000225','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000225',NULL,NOW(),'127.0.0.1'),
('1920000000000000226','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000226',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 同步补偿任务种子行
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」；实际调度由 Registrar 负责。
-- id 必须与 WmsConstants.PRODUCTION_SYNC_JOB_ID 同值；cron 与订单(3/5)、设备(7/5)、采购入库(9/5) 错峰。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000230','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsProductionSyncRetryJob','0 13/5 * * * ? *',NULL,'WMS生产入库同步补偿任务',0);

-- ------------------------------------------------ 验收种子（仅用于无 MES 环境的联调演示）
-- 成品物料 FG001：运行库原无「成品电路板」物料
INSERT IGNORE INTO `wms_material` (`id`,`material_code`,`material_name`,`category_id`,`spec`,`unit`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907410','FG001','成品电路板','2109933941019193346',NULL,'PCS','1',0,'admin',NOW());

INSERT IGNORE INTO `wms_work_order` (`id`,`work_order_no`,`material_id`,`material_code`,`material_name`,`product_type`,`plan_qty`,`batch_no`,`work_order_status`,`source`,`remark`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907420','WO-DEMO-SEMI','2111001706689810433','PBC001','未加工集成电路板','半成品',100.000,'B20261019A','生产中','手工','RK-002 验收种子',0,'admin',NOW()),
('2110885981815907421','WO-DEMO-FG','2110885981815907410','FG001','成品电路板','成品',50.000,'B20261019B','生产中','手工','RK-002 验收种子',0,'admin',NOW());
