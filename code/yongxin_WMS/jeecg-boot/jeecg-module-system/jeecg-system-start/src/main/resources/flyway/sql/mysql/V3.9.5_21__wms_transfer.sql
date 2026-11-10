-- =====================================================================
-- V3.9.5_21  CK-004 库存调拨：三张表 + 菜单/按钮权限
-- ---------------------------------------------------------------------
-- 依据：docs/superpowers/specs/2026-10-22-yx-wms-ck-004-transfer-design.md
--       docs/superpowers/plans/2026-10-22-yx-wms-ck-004-transfer.md（Task 1）
--
-- 设计要点（双时钟）：
--   exec_status            实物时钟：待执行/执行中/在途/待入库/实物完成/异常
--   sync_status            ERP 时钟：待同步/同步中/已提交待审核/已审核/ERP驳回/同步失败/无需同步
--   erp_document_status    金蝶原始状态 A/B/C/D（只读留痕）
--   status                 单据总状态，由 WmsTransferStatusResolver.deriveStatus 唯一派生
--   「已完成」当且仅当 exec_status=实物完成 且 sync_status=已审核（金蝶 C）
--
-- 回滚（已应用迁移不可改 checksum，以下仅供运维手工执行）：
--   DELETE FROM sys_role_permission WHERE permission_id LIKE '19100000000000004%';
--   DELETE FROM sys_permission      WHERE id LIKE '19100000000000004%';
--   DROP TABLE IF EXISTS wms_transfer_task;
--   DROP TABLE IF EXISTS wms_transfer_detail;
--   DROP TABLE IF EXISTS wms_transfer_order;
-- =====================================================================

-- ------------------------------------------------ 1) 调拨单（主表）
CREATE TABLE IF NOT EXISTS `wms_transfer_order` (
  `id`                    varchar(32)    NOT NULL COMMENT '主键',
  `transfer_no`           varchar(64)    NOT NULL COMMENT 'WMS 调拨单号，同时作为金蝶 FBillNo',
  `source`                varchar(16)    NOT NULL DEFAULT 'WMS' COMMENT '来源：WMS/ERP',
  `erp_bill_no`           varchar(64)    DEFAULT NULL COMMENT '金蝶单号（Save 成功后回填）',
  `erp_document_status`   varchar(8)     DEFAULT NULL COMMENT '金蝶原始状态 A/B/C/D（只读留痕）',
  `erp_org_code`          varchar(32)    DEFAULT NULL COMMENT '库存组织编码，默认 101',
  `out_warehouse_id`      varchar(32)    NOT NULL COMMENT '调出库区ID',
  `out_warehouse_code`    varchar(64)    DEFAULT NULL COMMENT '调出库区编码（=金蝶仓库编码）',
  `out_zone_code`         varchar(63)    DEFAULT NULL COMMENT '调出区域（米塔 zoneCode，可空）',
  `in_warehouse_id`       varchar(32)    NOT NULL COMMENT '调入库区ID',
  `in_warehouse_code`     varchar(64)    DEFAULT NULL COMMENT '调入库区编码（=金蝶仓库编码）',
  `in_zone_code`          varchar(63)    DEFAULT NULL COMMENT '调入区域（同仓搬运时必填）',
  `transfer_mode`         varchar(16)    NOT NULL COMMENT '调拨模式：跨仓/同仓区域',
  `exec_status`           varchar(16)    NOT NULL COMMENT '实物时钟状态',
  `sync_status`           varchar(16)    NOT NULL COMMENT 'ERP 时钟状态',
  `status`                varchar(16)    NOT NULL COMMENT '单据总状态（派生态）',
  `transit_location_id`   varchar(32)    DEFAULT NULL COMMENT '跨仓在途暂存库位ID',
  `approval_alert_time`   datetime       DEFAULT NULL COMMENT '审批超时告警已发时间（幂等）',
  `reject_reason`         varchar(255)   DEFAULT NULL COMMENT 'ERP 驳回原因',
  `reject_time`           datetime       DEFAULT NULL COMMENT 'ERP 驳回时间',
  `reject_handle_status`  varchar(16)    DEFAULT NULL COMMENT '驳回处置：待处置/已冲销/已人工关闭',
  `reverse_transfer_no`   varchar(64)    DEFAULT NULL COMMENT '冲销（反向）调拨单号',
  `retry_count`           int            NOT NULL DEFAULT 0 COMMENT '同步退避重试次数',
  `sync_fail_count`       int            NOT NULL DEFAULT 0 COMMENT '连续同步失败次数（≥3 告警）',
  `last_sync_error`       varchar(500)   DEFAULT NULL COMMENT '最近同步失败原因',
  `last_sync_time`        datetime       DEFAULT NULL COMMENT '最近同步时间',
  `operator`              varchar(50)    DEFAULT NULL COMMENT '操作人',
  `submit_time`           datetime       DEFAULT NULL COMMENT '提交金蝶成功（进入 B）时间',
  `exec_finish_time`      datetime       DEFAULT NULL COMMENT '实物完成时间',
  `erp_approve_time`      datetime       DEFAULT NULL COMMENT '金蝶审批通过（C）时间',
  `complete_time`         datetime       DEFAULT NULL COMMENT '单据完成时间',
  `close_reason`          varchar(255)   DEFAULT NULL COMMENT '关单原因',
  `close_by`              varchar(50)    DEFAULT NULL COMMENT '关单人',
  `close_time`            datetime       DEFAULT NULL COMMENT '关单时间',
  `remark`                varchar(255)   DEFAULT NULL COMMENT '备注',
  `create_by`             varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`           datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`             varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`           datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`            bigint         NOT NULL DEFAULT 0 COMMENT '逻辑删除时间戳（0=未删除）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_transfer_no` (`transfer_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_transfer_status` (`status`) USING BTREE,
  INDEX `idx_wms_transfer_erp` (`erp_document_status`, `sync_status`) USING BTREE,
  INDEX `idx_wms_transfer_out_wh` (`out_warehouse_id`) USING BTREE,
  INDEX `idx_wms_transfer_in_wh` (`in_warehouse_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库存调拨单';

-- ------------------------------------------------ 2) 调拨明细
CREATE TABLE IF NOT EXISTS `wms_transfer_detail` (
  `id`                varchar(32)    NOT NULL COMMENT '主键',
  `transfer_id`       varchar(32)    NOT NULL COMMENT '调拨单ID',
  `line_no`           int            NOT NULL DEFAULT 1 COMMENT '行号',
  `material_id`       varchar(32)    NOT NULL COMMENT '物料ID',
  `material_code`     varchar(64)    DEFAULT NULL COMMENT '物料编码',
  `material_name`     varchar(128)   DEFAULT NULL COMMENT '物料名称',
  `batch_no`          varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号（空=执行时 FIFO 取批次）',
  `container_code`    varchar(64)    NOT NULL DEFAULT '' COMMENT '料箱/托盘编码（立体库为空串）',
  `unit`              varchar(16)    DEFAULT NULL COMMENT '单位',
  `transfer_qty`      decimal(12,3)  NOT NULL DEFAULT 0 COMMENT '计划调拨数量',
  `out_location_id`   varchar(32)    DEFAULT NULL COMMENT '实际调出库位ID',
  `out_location_code` varchar(64)    DEFAULT NULL COMMENT '实际调出库位编码',
  `in_location_id`    varchar(32)    DEFAULT NULL COMMENT '实际调入库位ID',
  `in_location_code`  varchar(64)    DEFAULT NULL COMMENT '实际调入库位编码（位置回调 stackCode）',
  `actual_qty`        decimal(12,3)  NOT NULL DEFAULT 0 COMMENT '实发数量',
  `remark`            varchar(255)   DEFAULT NULL COMMENT '备注',
  `create_by`         varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`       datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`         varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`       datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`        bigint         NOT NULL DEFAULT 0 COMMENT '逻辑删除时间戳',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_transfer_detail` (`transfer_id`) USING BTREE,
  INDEX `idx_wms_transfer_detail_container` (`container_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='库存调拨明细';

-- ------------------------------------------------ 3) 调拨搬运任务（同仓区域）
CREATE TABLE IF NOT EXISTS `wms_transfer_task` (
  `id`             varchar(32)    NOT NULL COMMENT '主键',
  `transfer_id`    varchar(32)    NOT NULL COMMENT '调拨单ID',
  `detail_id`      varchar(32)    DEFAULT NULL COMMENT '调拨明细ID',
  `inventory_id`   varchar(32)    DEFAULT NULL COMMENT '锁定的库存行ID（wms_inventory.id）',
  `allocated_qty`  decimal(12,3)  NOT NULL DEFAULT 0 COMMENT '本行锁定量',
  `task_no`        varchar(64)    NOT NULL COMMENT 'RMS 任务号（前缀 RMS）',
  `container_code` varchar(64)    NOT NULL DEFAULT '' COMMENT '容器编码',
  `zone_code`      varchar(63)    DEFAULT NULL COMMENT '目标区域编码',
  `task_status`    varchar(16)    NOT NULL DEFAULT '未下发' COMMENT '未下发/已受理/执行中/已完成/失败/已关闭',
  `task_error`     varchar(500)   DEFAULT NULL COMMENT '失败原因（受理成功不写）',
  `mocked`         tinyint(1)     NOT NULL DEFAULT 0 COMMENT '是否模拟受理（RMS 地址未配置）',
  `feedback_time`  datetime       DEFAULT NULL COMMENT '设备回执时间',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`     bigint         NOT NULL DEFAULT 0 COMMENT '逻辑删除时间戳',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_transfer_task_no` (`task_no`) USING BTREE,
  INDEX `idx_wms_transfer_task_transfer` (`transfer_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='调拨搬运任务';

-- ------------------------------------------------ 4) 菜单：库存管理（顶层，sort=93，与入库 91 / 出库 92 平级）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000401','','库存管理','/wms/transfer','layouts/default/index',1,'','/wms/transfer/list',0,NULL,'0',93.00,0,'ant-design:swap-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000402','1910000000000000401','库存调拨','/wms/transfer/list','wms/transfer/index',1,'','',1,NULL,'0',1.00,0,'ant-design:swap-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 5) 按钮权限（与 WmsTransferController 一一对应）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000411','1910000000000000402','调拨-查询',NULL,NULL,0,NULL,'',2,'wms:transfer:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000412','1910000000000000402','调拨-新建',NULL,NULL,0,NULL,'',2,'wms:transfer:add','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000413','1910000000000000402','调拨-下发',NULL,NULL,0,NULL,'',2,'wms:transfer:dispatch','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000414','1910000000000000402','调拨-执行确认',NULL,NULL,0,NULL,'',2,'wms:transfer:execute','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000415','1910000000000000402','调拨-ERP同步',NULL,NULL,0,NULL,'',2,'wms:transfer:sync','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000416','1910000000000000402','调拨-反向冲销',NULL,NULL,0,NULL,'',2,'wms:transfer:reverse','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000417','1910000000000000402','调拨-关单',NULL,NULL,0,NULL,'',2,'wms:transfer:close','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 6) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000401','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000401',NULL,NOW(),'127.0.0.1'),
('1920000000000000402','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000402',NULL,NOW(),'127.0.0.1'),
('1920000000000000411','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000411',NULL,NOW(),'127.0.0.1'),
('1920000000000000412','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000412',NULL,NOW(),'127.0.0.1'),
('1920000000000000413','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000413',NULL,NOW(),'127.0.0.1'),
('1920000000000000414','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000414',NULL,NOW(),'127.0.0.1'),
('1920000000000000415','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000415',NULL,NOW(),'127.0.0.1'),
('1920000000000000416','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000416',NULL,NOW(),'127.0.0.1'),
('1920000000000000417','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000417',NULL,NOW(),'127.0.0.1');
