-- =====================================================================
-- V3.9.5_23  CK-002 销售订单方向反转（ERP → WMS）：快照表 + 同步按钮 + 拉取任务
-- ---------------------------------------------------------------------
-- 依据：docs/superpowers/specs/2026-10-22-yx-wms-ck-002-sales-order-direction-design.md
--
-- 方向变更：WMS 不再创建/回写 SAL_SaleOrder，只读取「已审核(C)」的销售订单生成出库任务；
--           确认出库仍回写 SAL_OUTSTOCK（方向不变）。
-- 历史列说明：wms_outbound_order.sale_sync_* / override_* 保留（已应用迁移不可逆 + 历史留痕），
--           代码不再读写。
--
-- 回滚（已应用迁移不可改 checksum，以下仅供运维手工执行）：
--   DELETE FROM sys_role_permission WHERE id = '1920000000000000321';
--   DELETE FROM sys_permission      WHERE id = '1910000000000000321';
--   DELETE FROM sys_quartz_job      WHERE id = '1920000000000000360';
--   DROP TABLE IF EXISTS wms_sales_order_detail;
--   DROP TABLE IF EXISTS wms_sales_order;
-- =====================================================================

-- ------------------------------------------------ 1) 销售订单快照（表头）
CREATE TABLE IF NOT EXISTS `wms_sales_order` (
  `id`                  varchar(32)   NOT NULL COMMENT '主键',
  `sales_order_no`      varchar(64)   NOT NULL COMMENT '金蝶销售订单号（SAL_SaleOrder.FBillNo）',
  `customer_code`       varchar(64)   DEFAULT NULL COMMENT '客户编码（FCustId.FNumber）',
  `customer_name`       varchar(128)  DEFAULT NULL COMMENT '客户名称（FCustId.FName）',
  `sale_org_number`     varchar(32)   DEFAULT NULL COMMENT '销售组织编码（FSaleOrgId.FNumber）',
  `order_date`          datetime      DEFAULT NULL COMMENT '订单日期（FDate）',
  `erp_document_status` varchar(8)    DEFAULT NULL COMMENT '金蝶单据状态 A/B/C/D/Z',
  `delivery_date`       datetime      DEFAULT NULL COMMENT '最早要货日期（分录 FDeliveryDate 最小值）',
  `fetch_status`        varchar(16)   DEFAULT NULL COMMENT '拉取状态：待拉取/成功/失败',
  `fetch_error`         varchar(500)  DEFAULT NULL COMMENT '最近拉取失败原因',
  `fetch_fail_count`    int           NOT NULL DEFAULT 0 COMMENT '连续拉取失败次数（≥3 告警）',
  `fetch_time`          datetime      DEFAULT NULL COMMENT '最近拉取时间',
  `remark`              varchar(500)  DEFAULT NULL COMMENT '备注（FNote）',
  `create_by`           varchar(50)   DEFAULT NULL COMMENT '创建人',
  `create_time`         datetime      DEFAULT NULL COMMENT '创建时间',
  `update_by`           varchar(50)   DEFAULT NULL COMMENT '更新人',
  `update_time`         datetime      DEFAULT NULL COMMENT '更新时间',
  `deleted_at`          bigint        NOT NULL DEFAULT 0 COMMENT '逻辑删除时间戳（0=未删除）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_sales_order_no` (`sales_order_no`, `deleted_at`) USING BTREE,
  INDEX `idx_wms_so_status` (`erp_document_status`) USING BTREE,
  INDEX `idx_wms_so_fetch` (`fetch_status`, `fetch_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='销售订单快照（ERP→WMS）';

-- ------------------------------------------------ 2) 销售订单分录快照
CREATE TABLE IF NOT EXISTS `wms_sales_order_detail` (
  `id`             varchar(32)    NOT NULL COMMENT '主键',
  `sales_order_id` varchar(32)    NOT NULL COMMENT '所属快照ID',
  `line_no`        int            NOT NULL DEFAULT 1 COMMENT '行号（按金蝶分录顺序）',
  `material_id`    varchar(32)    DEFAULT NULL COMMENT 'WMS 物料ID（按编码反查，可空=未建档）',
  `material_code`  varchar(64)    DEFAULT NULL COMMENT '物料编码（FMaterialId.FNumber）',
  `material_name`  varchar(128)   DEFAULT NULL COMMENT '物料名称（FMaterialId.FName）',
  `unit`           varchar(32)    DEFAULT NULL COMMENT '单位编码（FUnitID.FNumber，如 zhi）',
  `order_qty`      decimal(18,3)  NOT NULL DEFAULT 0 COMMENT '订单数量（FQty）',
  `delivery_date`  datetime       DEFAULT NULL COMMENT '分录要货日期（FDeliveryDate）',
  `shipped_qty`    decimal(18,3)  NOT NULL DEFAULT 0 COMMENT 'WMS 已出库数量（确认出库后汇总）',
  `create_by`      varchar(50)    DEFAULT NULL COMMENT '创建人',
  `create_time`    datetime       DEFAULT NULL COMMENT '创建时间',
  `update_by`      varchar(50)    DEFAULT NULL COMMENT '更新人',
  `update_time`    datetime       DEFAULT NULL COMMENT '更新时间',
  `deleted_at`     bigint         NOT NULL DEFAULT 0 COMMENT '逻辑删除时间戳',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_so_detail` (`sales_order_id`) USING BTREE,
  INDEX `idx_wms_so_detail_material` (`material_code`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='销售订单分录快照（ERP→WMS）';

-- ------------------------------------------------ 3) 按钮权限：销售订单同步
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000321','1910000000000000302','出库-销售订单同步',NULL,NULL,0,NULL,'',2,'wms:outbound:sales-sync','1',11.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000321','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000321',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 4) 销售订单拉取任务种子
-- 仅为让任务在「定时任务管理」UI 可见并支持「立即执行」；实际调度由 Registrar 注册。
-- cron `0 29/15 * * * ? *`：每 15 分钟落在 :29，与其它补偿任务错峰。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000360','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsSalesOrderSyncJob','0 29/15 * * * ? *',NULL,'WMS销售订单拉取任务',0);
