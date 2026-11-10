-- ============================================================================
-- 永鑫WMS · 采购入库流程（蓝图流程 YX-WMS-RK-001）
-- 内容：wms_inbound_order / wms_inbound_detail / wms_inspection_task / wms_inventory 建表
--       + 「基础管理」改名「基础数据」+ 新目录「入库管理」与两个菜单 + 按钮权限 + admin 授权
--       + 线边柜库区与库位种子 + 入库 ERP 同步补偿任务种子行
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS / INSERT IGNORE / 带条件 UPDATE）
-- 口径：单据合一状态机与库位占用口径见 docs/superpowers/specs/2026-10-19-yx-wms-rk-001-inbound-design.md
-- ============================================================================

-- ---------------------------------------------------------------- 入库单（收料通知单与采购入库单合一）
CREATE TABLE IF NOT EXISTS `wms_inbound_order` (
  `id`                varchar(32)  NOT NULL COMMENT '主键',
  `order_no`          varchar(64)  NOT NULL COMMENT '入库单号（兼收料通知单号，SL前缀）',
  `inbound_type`      varchar(20)  NOT NULL DEFAULT '采购入库' COMMENT '入库类型：采购入库',
  `purchase_order_no` varchar(64)  NOT NULL COMMENT '采购订单号（wms_order.order_no，仅存在性校验，允许多张收料单分批到货）',
  `supplier_id`       varchar(32)  DEFAULT NULL COMMENT '供应商ID（自采购订单带出）',
  `supplier_code`     varchar(64)  DEFAULT NULL COMMENT '供应商编码（冗余）',
  `supplier_name`     varchar(100) DEFAULT NULL COMMENT '供应商名称（冗余）',
  `inbound_status`    varchar(20)  NOT NULL DEFAULT '待检验' COMMENT '单据状态：待检验/待分配/待上架/已入库/已审核/已拒收/检验不合格/作废',
  `reject_reason`     varchar(500) DEFAULT NULL COMMENT '拒收/作废原因',
  `sync_status`       varchar(20)  NOT NULL DEFAULT '待同步' COMMENT '同步状态：待同步/同步中/同步成功/同步失败',
  `retry_count`       int(11)      NOT NULL DEFAULT 0 COMMENT '最近一次同步尝试中的重试次数',
  `last_sync_error`   varchar(500) DEFAULT NULL COMMENT '最后一次同步失败原因',
  `last_sync_time`    datetime     DEFAULT NULL COMMENT '最后一次同步尝试时间',
  `audit_by`          varchar(50)  DEFAULT NULL COMMENT '审核人（自动审核为 system）',
  `audit_time`        datetime     DEFAULT NULL COMMENT '审核时间',
  `deleted_at`        bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`         varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`       datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`         varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`       datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_inbound_order_no` (`order_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_inbound_po_no` (`purchase_order_no`) USING BTREE,
  INDEX `idx_wms_inbound_status` (`inbound_status`) USING BTREE,
  INDEX `idx_wms_inbound_sync` (`sync_status`, `retry_count`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='采购入库单（兼收料通知单）';

-- ---------------------------------------------------------------- 入库明细
CREATE TABLE IF NOT EXISTS `wms_inbound_detail` (
  `id`              varchar(32)    NOT NULL COMMENT '主键',
  `inbound_id`      varchar(32)    NOT NULL COMMENT '入库单ID（wms_inbound_order.id）',
  `material_id`     varchar(32)    NOT NULL COMMENT '物料ID（wms_material.id）',
  `material_code`   varchar(64)    DEFAULT NULL COMMENT '物料编码（冗余）',
  `material_name`   varchar(100)   DEFAULT NULL COMMENT '物料名称（冗余）',
  `quantity`        decimal(12,3)  NOT NULL COMMENT '应收数量',
  `actual_quantity` decimal(12,3)  DEFAULT NULL COMMENT '实收数量（上架确认时录入；0=短装跳过）',
  `location_id`     varchar(32)    DEFAULT NULL COMMENT '库位ID（分配后写入，wms_location.id）',
  `batch_no`        varchar(64)    NOT NULL COMMENT '批次号',
  `deleted_at`      bigint         NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`       varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`     datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_inbound_detail_inbound` (`inbound_id`) USING BTREE,
  INDEX `idx_wms_inbound_detail_material` (`material_id`) USING BTREE,
  INDEX `idx_wms_inbound_detail_location` (`location_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='采购入库明细';

-- ---------------------------------------------------------------- 检验任务（整单级）
CREATE TABLE IF NOT EXISTS `wms_inspection_task` (
  `id`             varchar(32)  NOT NULL COMMENT '主键',
  `task_no`        varchar(64)  NOT NULL COMMENT '检验任务号（JY前缀）',
  `inbound_id`     varchar(32)  NOT NULL COMMENT '入库单ID（整单一个任务）',
  `inspect_status` varchar(20)  NOT NULL DEFAULT '待检验' COMMENT '检验状态：待检验/合格/不合格',
  `result_remark`  varchar(500) DEFAULT NULL COMMENT '检验结果说明（不合格时必填原因）',
  `inspector`      varchar(50)  DEFAULT NULL COMMENT '检验员',
  `inspect_time`   datetime     DEFAULT NULL COMMENT '检验时间',
  `deleted_at`     bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`      varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_inspection_task_no` (`task_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_inspection_inbound` (`inbound_id`) USING BTREE,
  INDEX `idx_wms_inspection_status` (`inspect_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='来料检验任务';

-- ---------------------------------------------------------------- 库存（不做逻辑删除，行随聚合存在）
CREATE TABLE IF NOT EXISTS `wms_inventory` (
  `id`           varchar(32)    NOT NULL COMMENT '主键',
  `material_id`  varchar(32)    NOT NULL COMMENT '物料ID（wms_material.id）',
  `location_id`  varchar(32)    NOT NULL COMMENT '库位ID（wms_location.id）',
  `batch_no`     varchar(64)    NOT NULL COMMENT '批次号',
  `quantity`     decimal(12,3)  NOT NULL DEFAULT 0 COMMENT '库存数量',
  `create_by`    varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`  datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`    varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`  datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_inv_mat_loc_batch` (`material_id`, `location_id`, `batch_no`) USING BTREE,
  INDEX `idx_wms_inv_location` (`location_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库存';

-- ------------------------------------------------ 既有目录改名：基础管理 → 基础数据
-- 带 name='基础管理' 条件，重复执行不会覆盖后续的人工改名
UPDATE `sys_permission` SET `name` = '基础数据'
 WHERE `id` = '1910000000000000001' AND `name` = '基础管理';

-- ------------------------------------------------ 目录「入库管理」（与基础数据平级，menu_type=0）
-- redirect 直接指向第一个子菜单（V3.9.5_7 的教训：目录无 redirect 会 404）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000101','','入库管理','/wms/inbound','layouts/default/index',1,'','/wms/inbound/receive',0,NULL,'0',91.00,0,'ant-design:import-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 菜单（menu_type=1）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000102','1910000000000000101','采购入库','/wms/inbound/receive','wms/inbound/index',1,'','',1,NULL,'0',1.00,0,'ant-design:inbox-in',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000103','1910000000000000101','检验任务','/wms/inbound/inspection','wms/inbound/InspectionList',1,'','',1,NULL,'0',2.00,0,'ant-design:audit-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000111','1910000000000000102','入库-收料',NULL,NULL,0,NULL,'',2,'wms:inbound:receive','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000112','1910000000000000102','入库-库位分配',NULL,NULL,0,NULL,'',2,'wms:inbound:allocate','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000113','1910000000000000102','入库-上架确认',NULL,NULL,0,NULL,'',2,'wms:inbound:putaway','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000114','1910000000000000102','入库-作废',NULL,NULL,0,NULL,'',2,'wms:inbound:void','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000115','1910000000000000102','入库-重新同步',NULL,NULL,0,NULL,'',2,'wms:inbound:sync','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000116','1910000000000000103','检验-提交结果',NULL,NULL,0,NULL,'',2,'wms:inspection:do','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ admin 角色授权（role_id 取自既有种子）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000151','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000101',NULL,NOW(),'127.0.0.1'),
('1920000000000000152','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000102',NULL,NOW(),'127.0.0.1'),
('1920000000000000153','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000103',NULL,NOW(),'127.0.0.1'),
('1920000000000000154','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000111',NULL,NOW(),'127.0.0.1'),
('1920000000000000155','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000112',NULL,NOW(),'127.0.0.1'),
('1920000000000000156','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000113',NULL,NOW(),'127.0.0.1'),
('1920000000000000157','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000114',NULL,NOW(),'127.0.0.1'),
('1920000000000000158','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000115',NULL,NOW(),'127.0.0.1'),
('1920000000000000159','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000116',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 线边柜库区与库位种子
-- 运行库原无「线边柜」库区（2026-10-19 实查），辅料腿的自动分配依赖它
INSERT IGNORE INTO `wms_warehouse` (`id`,`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907200','XB01','线边柜一组','线边柜','1',0,'admin',NOW());

INSERT IGNORE INTO `wms_location` (`id`,`location_code`,`warehouse_id`,`row_no`,`col_no`,`layer_no`,`status`,`deleted_at`,`create_by`,`create_time`) VALUES
('2110885981815907201','XB-G01','2110885981815907200',1,1,1,'空闲',0,'admin',NOW()),
('2110885981815907202','XB-G02','2110885981815907200',1,2,1,'空闲',0,'admin',NOW()),
('2110885981815907203','XB-G03','2110885981815907200',1,3,1,'空闲',0,'admin',NOW()),
('2110885981815907204','XB-G04','2110885981815907200',1,4,1,'空闲',0,'admin',NOW()),
('2110885981815907205','XB-G05','2110885981815907200',1,5,1,'空闲',0,'admin',NOW()),
('2110885981815907206','XB-G06','2110885981815907200',1,6,1,'空闲',0,'admin',NOW()),
('2110885981815907207','XB-G07','2110885981815907200',1,7,1,'空闲',0,'admin',NOW()),
('2110885981815907208','XB-G08','2110885981815907200',1,8,1,'空闲',0,'admin',NOW());

-- ------------------------------------------------ 补偿定时任务种子行
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」（适合验收演示）。
-- 实际调度由 WmsInboundSyncJobRegistrar 负责——本行不会被平台自动加载。
-- id 必须与 WmsConstants.INBOUND_SYNC_JOB_ID 同值；cron 与订单补偿任务（0 3/5）错峰。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000160','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsInboundSyncRetryJob','0 9/5 * * * ? *',NULL,'WMS入库单ERP同步补偿任务',0);

