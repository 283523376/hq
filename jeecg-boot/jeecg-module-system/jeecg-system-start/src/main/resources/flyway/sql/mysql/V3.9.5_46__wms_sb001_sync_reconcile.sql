-- ================================================================
-- SB-001 上位系统数据同步：对账四表 + 菜单权限
-- 设计：docs/superpowers/specs/2026-11-03-yx-wms-sb-001-上位系统同步-design.md（§2.5 / §5）
-- 口径：只对账不写账；粒度 = 仓库+物料+批次（库位/容器聚合掉）；
--       库存严格比对，流水只展示；快照即「待推送包」，差异清单可发起盘点。
-- 说明：四张表均不设 deleted_at（对账是时点快照，按保留期物理清理：
--       批次/快照/流水 180 天、差异 365 天，见 §2.9.1，Job 后续任务接入）；
--       无物理外键（应用层引用，遵循项目约定）。
-- ================================================================

-- ------------------------------------------------ 1) 对账批次表
CREATE TABLE IF NOT EXISTS `wms_sync_run` (
  `id`              varchar(36)    NOT NULL COMMENT '主键',
  `run_no`          varchar(64)    NOT NULL COMMENT '批次号：SB + yyyyMMddHHmmss + 3 位序号',
  `scope_type`      varchar(16)    NOT NULL COMMENT '范围：FULL 全量 / TODAY 今日变动 / WAREHOUSE 指定仓库',
  `warehouse_codes` varchar(500)   DEFAULT NULL COMMENT '指定仓库编码，逗号分隔（scope_type=WAREHOUSE 时用）',
  `window_start`    datetime       DEFAULT NULL COMMENT '时间窗起点',
  `window_end`      datetime       DEFAULT NULL COMMENT '时间窗终点',
  `status`          varchar(16)    NOT NULL DEFAULT '执行中' COMMENT '状态：执行中 / 成功 / 失败',
  `snapshot_count`  int            NOT NULL DEFAULT 0 COMMENT '快照行数',
  `diff_count`      int            NOT NULL DEFAULT 0 COMMENT '差异行数',
  `flow_count`      int            NOT NULL DEFAULT 0 COMMENT '流水行数',
  `retry_count`     int            NOT NULL DEFAULT 0 COMMENT '本次执行重试次数（0~3）',
  `cost_ms`         int            DEFAULT NULL COMMENT '耗时毫秒',
  `error_message`   varchar(1000)  DEFAULT NULL COMMENT '失败原因',
  `operator`        varchar(64)    NOT NULL COMMENT '操作人（异步线程无 Shiro 上下文，显式存储；定时任务写 system）',
  `create_by`       varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`     datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`     datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_run_no` (`run_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='SB-001 对账批次';

-- ------------------------------------------------ 2) 库存快照表（待推送包）
-- 匹配键 = 仓库编码+物料编码+批次号；仓库用 COALESCE(erp_stock_code, warehouse_code)，本期 1:1。
-- locked_qty 只附带展示，不计入对比（金蝶无「WMS 锁定」概念）。
CREATE TABLE IF NOT EXISTS `wms_sync_snapshot` (
  `id`             varchar(36)    NOT NULL COMMENT '主键',
  `run_id`         varchar(36)    NOT NULL COMMENT '批次ID（wms_sync_run.id）',
  `warehouse_code` varchar(64)    NOT NULL COMMENT '仓库编码（金蝶 FStockId.FNumber，WMS 与金蝶 1:1）',
  `material_code`  varchar(64)    NOT NULL COMMENT '物料编码',
  `material_name`  varchar(100)   DEFAULT NULL COMMENT '物料名称',
  `unit`           varchar(20)    DEFAULT NULL COMMENT '单位',
  `batch_no`       varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号（金蝶 FLot.FNumber 为空时归一化为空串）',
  `wms_qty`        decimal(20,6)  NOT NULL DEFAULT 0 COMMENT 'WMS 侧数量（仓库+物料+批次聚合，库位/容器已聚合掉）',
  `erp_qty`        decimal(20,6)  DEFAULT NULL COMMENT '金蝶即时库存数量（FBaseQty；仅WMS/无金蝶编码记 0）',
  `diff_qty`       decimal(20,6)  DEFAULT NULL COMMENT '差异 = WMS - ERP（多账为正、少账为负；仅WMS=wms、仅ERP=-erp）',
  `match_status`   varchar(16)    DEFAULT NULL COMMENT '匹配状态：一致 / 数量差异 / 仅WMS / 仅ERP（汇总阶段为 NULL，比对后回填）',
  `locked_qty`     decimal(20,6)  DEFAULT NULL COMMENT '出库锁定数量（仅附带展示，不计入对比）',
  `snapshot_time`  datetime       NOT NULL COMMENT '快照时点',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间（保留期清理依据）',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_snap_key` (`run_id`,`warehouse_code`,`material_code`,`batch_no`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='SB-001 库存快照（待推送包）';

-- ------------------------------------------------ 3) 同步流水表（仅展示，不参与比对）
CREATE TABLE IF NOT EXISTS `wms_sync_flow` (
  `id`             varchar(36)    NOT NULL COMMENT '主键',
  `run_id`         varchar(36)    NOT NULL COMMENT '批次ID（wms_sync_run.id）',
  `flow_type`      varchar(16)    NOT NULL COMMENT '类型：入库 / 出库 / 调拨 / 盘点',
  `direction`      varchar(8)     NOT NULL COMMENT '方向：IN / OUT（调拨按调出/调入拆两行）',
  `bill_no`        varchar(64)    NOT NULL COMMENT '业务单号',
  `warehouse_code` varchar(64)    NOT NULL COMMENT '仓库编码',
  `material_code`  varchar(64)    NOT NULL COMMENT '物料编码',
  `material_name`  varchar(100)   DEFAULT NULL COMMENT '物料名称',
  `batch_no`       varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号',
  `qty`            decimal(20,6)  NOT NULL COMMENT '数量',
  `biz_time`       datetime       NOT NULL COMMENT '业务时间（单据 update_time，盘点取 adjust_time）',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间（保留期清理依据）',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_run` (`run_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='SB-001 同步流水（仅展示）';

-- ------------------------------------------------ 4) 差异清单表
CREATE TABLE IF NOT EXISTS `wms_sync_diff` (
  `id`             varchar(36)    NOT NULL COMMENT '主键',
  `run_id`         varchar(36)    NOT NULL COMMENT '批次ID（wms_sync_run.id）',
  `warehouse_code` varchar(64)    NOT NULL COMMENT '仓库编码',
  `material_code`  varchar(64)    NOT NULL COMMENT '物料编码',
  `material_name`  varchar(100)   DEFAULT NULL COMMENT '物料名称',
  `batch_no`       varchar(64)    NOT NULL DEFAULT '' COMMENT '批次号',
  `wms_qty`        decimal(20,6)  DEFAULT NULL COMMENT 'WMS 侧数量（仅ERP 差异为 0）',
  `erp_qty`        decimal(20,6)  DEFAULT NULL COMMENT '金蝶侧数量（仅WMS 差异为 0）',
  `diff_qty`       decimal(20,6)  NOT NULL COMMENT '差异 = WMS - ERP（仅WMS=wms、仅ERP=-erp，不留 NULL）',
  `diff_type`      varchar(16)    NOT NULL COMMENT '差异类型：多账 / 少账 / 仅WMS / 仅ERP',
  `status`         varchar(16)    NOT NULL DEFAULT '待核对' COMMENT '状态：待核对 / 已核对 / 已忽略',
  `handle_by`      varchar(64)    DEFAULT NULL COMMENT '处理人',
  `handle_time`    datetime       DEFAULT NULL COMMENT '处理时间',
  `handle_remark`  varchar(500)   DEFAULT NULL COMMENT '处理备注（标记已核对/已忽略必填）',
  `stocktake_id`   varchar(36)    DEFAULT NULL COMMENT '发起盘点回填：盘点单ID（wms_stocktake_order.id）',
  `stocktake_no`   varchar(64)    DEFAULT NULL COMMENT '发起盘点回填：盘点单号',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间（保留期清理依据）',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_run` (`run_id`) USING BTREE,
  INDEX `idx_diff_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='SB-001 对账差异清单';

-- ------------------------------------------------ 5) 菜单：上位系统同步（顶级，sort=98）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000951','','上位系统同步','/wms/sync','layouts/default/index',1,'','/wms/sync/index',0,NULL,'0',98.00,0,'ant-design:cloud-sync-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000952','1910000000000000951','对账执行','/wms/sync/index','wms/sync/index',1,'','',1,NULL,'0',1.00,0,'ant-design:play-circle-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000953','1910000000000000951','差异清单','/wms/sync/diff','wms/sync/diff',1,'','',1,NULL,'0',2.00,0,'ant-design:diff-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000954','1910000000000000951','待推送包','/wms/sync/snapshot','wms/sync/snapshot',1,'','',1,NULL,'0',3.00,0,'ant-design:inbox-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000955','1910000000000000951','同步流水','/wms/sync/flow','wms/sync/flow',1,'','',1,NULL,'0',4.00,0,'ant-design:swap-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000956','1910000000000000951','同步日志','/wms/sync/log','wms/sync/log',1,'','',1,NULL,'0',5.00,0,'ant-design:file-text-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 6) 按钮权限（spec §5 的 5 个权限码）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000961','1910000000000000952','同步-执行对账',NULL,NULL,0,NULL,'',2,'wms:sync:run','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000962','1910000000000000953','差异-查看',NULL,NULL,0,NULL,'',2,'wms:sync:diff:view','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000963','1910000000000000953','差异-标记处理',NULL,NULL,0,NULL,'',2,'wms:sync:diff:handle','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000964','1910000000000000953','差异-发起盘点',NULL,NULL,0,NULL,'',2,'wms:sync:diff:stocktake','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000965','1910000000000000956','同步-查看日志',NULL,NULL,0,NULL,'',2,'wms:sync:log:view','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 7) admin 角色授权（授权 id 与权限 id 后缀同号）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000951','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000951',NULL,NOW(),'127.0.0.1'),
('1920000000000000952','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000952',NULL,NOW(),'127.0.0.1'),
('1920000000000000953','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000953',NULL,NOW(),'127.0.0.1'),
('1920000000000000954','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000954',NULL,NOW(),'127.0.0.1'),
('1920000000000000955','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000955',NULL,NOW(),'127.0.0.1'),
('1920000000000000956','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000956',NULL,NOW(),'127.0.0.1'),
('1920000000000000961','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000961',NULL,NOW(),'127.0.0.1'),
('1920000000000000962','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000962',NULL,NOW(),'127.0.0.1'),
('1920000000000000963','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000963',NULL,NOW(),'127.0.0.1'),
('1920000000000000964','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000964',NULL,NOW(),'127.0.0.1'),
('1920000000000000965','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000965',NULL,NOW(),'127.0.0.1');
