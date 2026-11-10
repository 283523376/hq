-- ============================================================================
-- 永鑫WMS · 供应商与生产订单管理（蓝图流程 YX-WMS-BD-002）
-- 内容：wms_supplier / wms_order 建表
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS）
-- 注意：菜单与定时任务种子在 V3.9.5_4 中
-- ============================================================================

-- ---------------------------------------------------------------- 供应商
CREATE TABLE IF NOT EXISTS `wms_supplier` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `supplier_code` varchar(64)  NOT NULL COMMENT '供应商编码',
  `supplier_name` varchar(100) NOT NULL COMMENT '供应商名称',
  `contact`       varchar(50)  DEFAULT NULL COMMENT '联系人',
  `phone`         varchar(30)  DEFAULT NULL COMMENT '联系电话',
  `supply_scope`  varchar(200) DEFAULT NULL COMMENT '供货范围',
  `status`        varchar(1)   NOT NULL DEFAULT '1' COMMENT '状态：1启用 0停用',
  `del_flag`      tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_supplier_code` (`supplier_code`) USING BTREE,
  INDEX `idx_wms_supplier_name` (`supplier_name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='供应商';

-- ---------------------------------------------------------------- 订单
CREATE TABLE IF NOT EXISTS `wms_order` (
  `id`              varchar(32)  NOT NULL COMMENT '主键',
  `order_no`        varchar(64)  NOT NULL COMMENT '订单号',
  `order_type`      varchar(20)  NOT NULL COMMENT '订单类型：公司订单/采购订单/委外订单',
  `supplier_id`     varchar(32)  DEFAULT NULL COMMENT '供应商ID（wms_supplier.id）',
  `order_status`    varchar(20)  NOT NULL DEFAULT '待审核' COMMENT '订单状态：待审核/已审核/已下达/已驳回',
  `audit_by`        varchar(50)  DEFAULT NULL COMMENT '审核人',
  `audit_time`      datetime     DEFAULT NULL COMMENT '审核时间',
  `audit_remark`    varchar(500) DEFAULT NULL COMMENT '审核意见（驳回时必填）',
  `dispatch_by`     varchar(50)  DEFAULT NULL COMMENT '下达人',
  `dispatch_time`   datetime     DEFAULT NULL COMMENT '下达时间',
  `sync_status`     varchar(20)  NOT NULL DEFAULT '待同步' COMMENT '同步状态：待同步/同步中/同步成功/同步失败',
  `retry_count`     int(11)      NOT NULL DEFAULT 0 COMMENT '最近一次同步尝试中的重试次数',
  `last_sync_error` varchar(500) DEFAULT NULL COMMENT '最后一次同步失败原因',
  `last_sync_time`  datetime     DEFAULT NULL COMMENT '最后一次同步尝试时间',
  `remark`          varchar(500) DEFAULT NULL COMMENT '备注',
  `del_flag`        tinyint(1)   NOT NULL DEFAULT 0 COMMENT '删除状态：0正常 1已删除',
  `create_by`       varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`     datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`       varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`     datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_order_no` (`order_no`) USING BTREE,
  INDEX `idx_wms_order_supplier` (`supplier_id`) USING BTREE,
  INDEX `idx_wms_order_type_status` (`order_type`, `order_status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='订单';
