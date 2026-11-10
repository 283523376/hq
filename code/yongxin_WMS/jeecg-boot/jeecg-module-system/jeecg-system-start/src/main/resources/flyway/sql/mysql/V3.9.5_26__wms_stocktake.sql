-- ============================================================================
-- 内容：库存盘点模块（spec 2026-10-26-outbound-subcontract-sample-stocktake §4/§5.3）
--   1) 盘点单 wms_stocktake_order
--   2) 盘点明细 wms_stocktake_detail
--   3) 盘点计划 wms_stocktake_plan
--   4) 库位冻结位 wms_location.stocktake_frozen
--   5) 菜单「库存盘点」+ 子菜单「定期盘点」「周期盘点」+ 按钮权限 + admin 授权
-- 说明：明细粒度对齐 wms_inventory 唯一键 (material_id, location_id, batch_no, container_code)。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `wms_stocktake_order` (
  `id`                    varchar(32)   NOT NULL COMMENT '主键',
  `stocktake_no`          varchar(32)   NOT NULL COMMENT '盘点单号（PD+日期+序号）',
  `stocktake_type`        varchar(20)   NOT NULL COMMENT '盘点类型：定期/周期',
  `scope_desc`            varchar(500)  DEFAULT NULL COMMENT '盘点范围说明',
  `status`                varchar(20)   NOT NULL COMMENT '状态：草稿/盘点中/差异确认/复核中/已调整/已归档',
  `diff_review_threshold` decimal(5,2)  DEFAULT NULL COMMENT '整单复核阈值（百分比，如 5.00）',
  `book_total`            decimal(18,4) NOT NULL DEFAULT 0 COMMENT '账面合计数量',
  `gain_total`            decimal(18,4) NOT NULL DEFAULT 0 COMMENT '盘盈合计',
  `loss_total`            decimal(18,4) NOT NULL DEFAULT 0 COMMENT '盘亏合计',
  `initiator`             varchar(50)   DEFAULT NULL COMMENT '盘点发起人',
  `reviewer`              varchar(50)   DEFAULT NULL COMMENT '复核人',
  `review_time`           datetime      DEFAULT NULL COMMENT '复核时间',
  `adjust_time`           datetime      DEFAULT NULL COMMENT '调整时间',
  `archive_time`          datetime      DEFAULT NULL COMMENT '归档时间',
  `erp_sync_status`       varchar(20)   DEFAULT NULL COMMENT 'ERP 回写状态',
  `erp_bill_no`           varchar(200)  DEFAULT NULL COMMENT 'ERP 回写单据号（盘盈/盘亏）',
  `remark`                varchar(500)  DEFAULT NULL COMMENT '备注',
  `deleted_at`            bigint        NOT NULL DEFAULT 0 COMMENT '删除时间戳：0 正常',
  `create_by`             varchar(50)   DEFAULT NULL COMMENT '创建人',
  `create_time`           datetime      DEFAULT NULL COMMENT '创建时间',
  `update_by`             varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`           datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_stocktake_no` (`stocktake_no`) USING BTREE,
  INDEX `idx_wms_stocktake_status` (`status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='盘点单';

CREATE TABLE IF NOT EXISTS `wms_stocktake_detail` (
  `id`             varchar(32)   NOT NULL COMMENT '主键',
  `stocktake_id`   varchar(32)   NOT NULL COMMENT '盘点单ID',
  `location_id`    varchar(32)   DEFAULT NULL COMMENT '库位ID',
  `location_code`  varchar(64)   DEFAULT NULL COMMENT '库位编码（冗余，便于导入与展示）',
  `material_id`    varchar(32)   DEFAULT NULL COMMENT '物料ID',
  `material_code`  varchar(64)   DEFAULT NULL COMMENT '物料编码（冗余）',
  `batch_no`       varchar(64)   DEFAULT NULL COMMENT '批次号',
  `container_code` varchar(64)   DEFAULT NULL COMMENT '容器/料箱编码（立库为空）',
  `book_qty`       decimal(18,4) NOT NULL DEFAULT 0 COMMENT '账面数量快照',
  `actual_qty`     decimal(18,4) DEFAULT NULL COMMENT '实盘数量',
  `diff_qty`       decimal(18,4) DEFAULT NULL COMMENT '差异数量（实盘-账面）',
  `diff_reason`    varchar(200)  DEFAULT NULL COMMENT '差异原因',
  `recheck_flag`   varchar(1)    NOT NULL DEFAULT '0' COMMENT '是否需重盘：1是 0否',
  `adjusted`       varchar(1)    NOT NULL DEFAULT '0' COMMENT '是否已调整：1是 0否',
  `deleted_at`     bigint        NOT NULL DEFAULT 0 COMMENT '删除时间戳：0 正常',
  `create_by`      varchar(50)   DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime      DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime      DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_stocktake_detail_order` (`stocktake_id`) USING BTREE,
  INDEX `idx_wms_stocktake_detail_loc` (`location_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='盘点明细';

CREATE TABLE IF NOT EXISTS `wms_stocktake_plan` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `plan_name`     varchar(100) NOT NULL COMMENT '计划名称',
  `plan_type`     varchar(20)  NOT NULL COMMENT '计划类型：定期/周期',
  `cron_expr`     varchar(50)  DEFAULT NULL COMMENT '定期计划 cron 表达式',
  `warehouse_id`  varchar(32)  DEFAULT NULL COMMENT '盘点仓库ID（空=全部）',
  `rolling_size`  int          DEFAULT NULL COMMENT '周期计划每期圈定的库位数',
  `last_run_time` datetime     DEFAULT NULL COMMENT '上次执行时间（周期轮转游标）',
  `enabled`       varchar(1)   NOT NULL DEFAULT '0' COMMENT '是否启用：1是 0否',
  `deleted_at`    bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳：0 正常',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='盘点计划';

ALTER TABLE `wms_location`
    ADD COLUMN `stocktake_frozen` varchar(1) NOT NULL DEFAULT '0' COMMENT '盘点冻结：1冻结 0正常';

-- ------------------------------------------------ 菜单：库存盘点（顶层，sort=94，与入库 91 / 出库 92 / 库存管理 93 平级）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000501','','库存盘点','/wms/stocktake','layouts/default/index',1,'','/wms/stocktake/list',0,NULL,'0',94.00,0,'ant-design:audit-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000502','1910000000000000501','定期盘点','/wms/stocktake/list','wms/stocktake/index',1,'','',1,NULL,'0',1.00,0,'ant-design:calendar-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000503','1910000000000000501','周期盘点','/wms/stocktake/cycle','wms/stocktake/index',1,'','',1,NULL,'0',2.00,0,'ant-design:sync-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 按钮权限（与 WmsStocktakeController 一一对应）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000511','1910000000000000502','盘点-查询',NULL,NULL,0,NULL,'',2,'wms:stocktake:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000512','1910000000000000502','盘点-新建',NULL,NULL,0,NULL,'',2,'wms:stocktake:add','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000513','1910000000000000502','盘点-录入实盘',NULL,NULL,0,NULL,'',2,'wms:stocktake:record','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000514','1910000000000000502','盘点-差异确认',NULL,NULL,0,NULL,'',2,'wms:stocktake:confirm','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000515','1910000000000000502','盘点-复核',NULL,NULL,0,NULL,'',2,'wms:stocktake:review','1',5.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000516','1910000000000000502','盘点-调整库存',NULL,NULL,0,NULL,'',2,'wms:stocktake:adjust','1',6.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000517','1910000000000000502','盘点-归档',NULL,NULL,0,NULL,'',2,'wms:stocktake:archive','1',7.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000501','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000501',NULL,NOW(),'127.0.0.1'),
('1920000000000000502','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000502',NULL,NOW(),'127.0.0.1'),
('1920000000000000503','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000503',NULL,NOW(),'127.0.0.1'),
('1920000000000000511','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000511',NULL,NOW(),'127.0.0.1'),
('1920000000000000512','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000512',NULL,NOW(),'127.0.0.1'),
('1920000000000000513','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000513',NULL,NOW(),'127.0.0.1'),
('1920000000000000514','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000514',NULL,NOW(),'127.0.0.1'),
('1920000000000000515','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000515',NULL,NOW(),'127.0.0.1'),
('1920000000000000516','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000516',NULL,NOW(),'127.0.0.1'),
('1920000000000000517','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000517',NULL,NOW(),'127.0.0.1');
