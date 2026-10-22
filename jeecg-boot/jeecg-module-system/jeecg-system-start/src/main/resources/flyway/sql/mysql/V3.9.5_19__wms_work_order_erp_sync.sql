-- ============================================================================
-- 永鑫WMS · BD-002 生产工单 ERP 同步改造（蓝图流程 YX-WMS-BD-002，V2.2）
-- 内容：
--       + wms_work_order 新增 ERP 同步字段（erp_document_status/sync_status/
--         retry_count/sync_fail_count/last_sync_error/last_sync_time）
--       + 工单状态/来源列注释与默认值更新（待生产/已下达/生产中/已完成/已关闭；ERP/MES/手工）
--       + 历史数据回填：MES/手工来源 sync_status='无需同步'（评审 I3）
--       + 同步日志表 wms_sync_log（SB-001 共用底座，本期由工单同步先行写入）
--       + 菜单：生产工单迁至基础数据目录；入库管理 redirect 改指生产入库；
--         下线 工单-新增/编辑/删除 按钮并清理授权；新增 工单-同步 按钮
--       + 定时任务种子：工单 ERP 同步补偿（id 与 WmsConstants.WORK_ORDER_SYNC_JOB_ID 同值）
-- 说明：本脚本可重复执行（information_schema 判存在 / INSERT IGNORE / 带条件 UPDATE）
-- 口径：金蝶 C(已审核)→已下达、A/B→待生产、D 及未知跳过；状态合并 rank-max，
--       已关闭为终态；来源新代码必须显式写（默认值仅为历史兼容）。
-- 回滚（慎用：丢失同步留痕）：
--   UPDATE `wms_work_order` SET `sync_status`='待同步' WHERE `source` IN ('MES','手工');
--   ALTER TABLE `wms_work_order`
--     DROP INDEX `idx_wms_work_order_sync`,
--     DROP COLUMN `erp_document_status`, DROP COLUMN `sync_status`, DROP COLUMN `retry_count`,
--     DROP COLUMN `sync_fail_count`, DROP COLUMN `last_sync_error`, DROP COLUMN `last_sync_time`;
--   DROP TABLE IF EXISTS `wms_sync_log`;  -- 确认无其他流程写入后再执行
--   UPDATE `sys_permission` SET `parent_id`='1910000000000000101', `sort_no`=3.00 WHERE `id`='1910000000000000201';
--   UPDATE `sys_permission` SET `redirect`='/wms/work-order' WHERE `id`='1910000000000000101';
--   UPDATE `sys_permission` SET `del_flag`=0 WHERE `id` IN ('1910000000000000211','1910000000000000212','1910000000000000213');
--   UPDATE `sys_permission` SET `del_flag`=1 WHERE `id`='1910000000000000214';
--   DELETE FROM `sys_quartz_job` WHERE `id`='1920000000000000340';
-- ============================================================================

-- ------------------------------------------------ 工单表新增同步字段（判存在，幂等）
SET @col_erp_status := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'erp_document_status'
);
SET @ddl_erp_status := IF(@col_erp_status = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `erp_document_status` varchar(10) DEFAULT NULL COMMENT ''金蝶FDocumentStatus原始值（A/B/C/D…），只读留痕'' AFTER `source`',
    'SELECT 1');
PREPARE stmt FROM @ddl_erp_status; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_sync_status := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'sync_status'
);
SET @ddl_sync_status := IF(@col_sync_status = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `sync_status` varchar(20) NOT NULL DEFAULT ''待同步'' COMMENT ''同步状态：待同步/同步中/同步成功/同步失败/无需同步'' AFTER `erp_document_status`',
    'SELECT 1');
PREPARE stmt FROM @ddl_sync_status; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_retry := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'retry_count'
);
SET @ddl_retry := IF(@col_retry = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `retry_count` int NOT NULL DEFAULT 0 COMMENT ''连续失败次数（成功清零）'' AFTER `sync_status`',
    'SELECT 1');
PREPARE stmt FROM @ddl_retry; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_fail_cnt := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'sync_fail_count'
);
SET @ddl_fail_cnt := IF(@col_fail_cnt = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `sync_fail_count` int NOT NULL DEFAULT 0 COMMENT ''累计失败次数（告警阈值用）'' AFTER `retry_count`',
    'SELECT 1');
PREPARE stmt FROM @ddl_fail_cnt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_err := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'last_sync_error'
);
SET @ddl_err := IF(@col_err = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `last_sync_error` varchar(500) DEFAULT NULL COMMENT ''最近一次同步失败原因'' AFTER `sync_fail_count`',
    'SELECT 1');
PREPARE stmt FROM @ddl_err; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_time := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND COLUMN_NAME = 'last_sync_time'
);
SET @ddl_time := IF(@col_time = 0,
    'ALTER TABLE `wms_work_order` ADD COLUMN `last_sync_time` datetime DEFAULT NULL COMMENT ''最近一次成功同步时间'' AFTER `last_sync_error`',
    'SELECT 1');
PREPARE stmt FROM @ddl_time; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_sync := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wms_work_order'
       AND INDEX_NAME = 'idx_wms_work_order_sync'
);
SET @ddl_idx_sync := IF(@idx_sync = 0,
    'ALTER TABLE `wms_work_order` ADD INDEX `idx_wms_work_order_sync` (`sync_status`, `last_sync_time`)',
    'SELECT 1');
PREPARE stmt FROM @ddl_idx_sync; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------ 状态/来源列注释与默认值（幂等：MODIFY 可重复执行）
ALTER TABLE `wms_work_order`
  MODIFY COLUMN `work_order_status` varchar(20) NOT NULL DEFAULT '已下达'
    COMMENT '工单状态：待生产/已下达/生产中/已完成/已关闭（已关闭为终态；默认值仅兜底，新代码必须显式写状态）',
  MODIFY COLUMN `source` varchar(20) DEFAULT 'MES'
    COMMENT '来源：ERP/MES/手工（手工仅历史数据；默认值仅为历史兼容，新代码必须显式写来源）';

-- ------------------------------------------------ 历史数据回填（评审 I3，幂等）
UPDATE `wms_work_order` SET `sync_status` = '无需同步'
WHERE `source` IN ('MES', '手工') AND `sync_status` = '待同步';

-- ------------------------------------------------ 同步日志表（SB-001 共用底座）
CREATE TABLE IF NOT EXISTS `wms_sync_log` (
  `id`               varchar(32)  NOT NULL COMMENT '主键',
  `direction`        varchar(20)  NOT NULL COMMENT '方向：ERP_TO_WMS/WMS_TO_ERP/WMS_TO_MES',
  `object_type`      varchar(30)  NOT NULL COMMENT '对象：WORK_ORDER/SALES_ORDER/INBOUND/OUTBOUND/TRANSFER/INVENTORY',
  `object_no`        varchar(64)  DEFAULT NULL COMMENT '业务单号',
  `target`           varchar(30)  DEFAULT NULL COMMENT '对端：KINGDEE/MES/WCS/RMS',
  `status`           varchar(20)  NOT NULL COMMENT '结果：同步成功/同步失败（对齐WmsConstants.SYNC_STATUS_*）',
  `retry_count`      int          NOT NULL DEFAULT 0 COMMENT '本单当前连续失败次数',
  `request_payload`  mediumtext   COMMENT '请求报文（脱敏，>8KB截断）',
  `response_payload` mediumtext   COMMENT '响应报文（脱敏，>8KB截断）',
  `error_message`    varchar(500) DEFAULT NULL COMMENT '失败原因',
  `cost_ms`          int          DEFAULT NULL COMMENT '耗时毫秒',
  `create_by`        varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`      datetime     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_sync_log_obj` (`object_type`, `object_no`, `create_time`) USING BTREE,
  INDEX `idx_wms_sync_log_status` (`status`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='上位系统同步日志';

-- ------------------------------------------------ 菜单迁移（评审：蓝图要求从「基础管理」进入）
-- 生产工单菜单：入库管理(1910000000000000101) → 基础数据(1910000000000000001)，sort=2.00（物料1.00 与 库区4.00 之间）
UPDATE `sys_permission` SET `parent_id` = '1910000000000000001', `sort_no` = 2.00
WHERE `id` = '1910000000000000201' AND `parent_id` = '1910000000000000101';

-- 入库管理目录 redirect 改指生产入库（工单迁走后原 redirect 会 404，V3.9.5_7 的教训）
UPDATE `sys_permission` SET `redirect` = '/wms/production/inbound'
WHERE `id` = '1910000000000000101' AND `redirect` = '/wms/work-order';

-- 下线 手工新增/编辑/删除 按钮（删除守卫随端点一并下线）
UPDATE `sys_permission` SET `del_flag` = 1
WHERE `id` IN ('1910000000000000211', '1910000000000000212', '1910000000000000213') AND `del_flag` = 0;

-- 清理角色对已下线按钮的授权（回滚见脚本头；授权行删除不影响功能，仅防残留）
DELETE FROM `sys_role_permission`
WHERE `permission_id` IN ('1910000000000000211', '1910000000000000212', '1910000000000000213');

-- 新增 工单-同步 按钮 + admin 授权
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000214','1910000000000000201','工单-同步',NULL,NULL,0,NULL,'',2,'wms:work-order:sync','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000341','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000214',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 定时任务种子（实际调度由 WmsWorkOrderSyncJobRegistrar 负责）
-- id 必须与 WmsConstants.WORK_ORDER_SYNC_JOB_ID 同值；cron 与订单(3/5)、设备(7/5)、采购入库(9/5)、
-- 生产入库(13/5)、出库(17/5) 错峰。
INSERT IGNORE INTO `sys_quartz_job` (`id`,`create_by`,`create_time`,`del_flag`,`update_by`,`update_time`,`job_class_name`,`cron_expression`,`parameter`,`description`,`status`) VALUES
('1920000000000000340','admin',NOW(),0,NULL,NULL,'org.jeecg.modules.wms.job.WmsWorkOrderSyncJob','0 1/5 * * * ? *',NULL,'WMS生产工单ERP同步任务',0);
