-- ================================================================
-- XT-002 操作日志留痕：审计表 + 不可变触发器 + 菜单权限 + 保留期配置
-- 设计：docs/superpowers/specs/2026-11-02-yx-wms-xt-002-操作日志留痕-design.md
-- ================================================================

-- ------------------------------------------------ 1) 审计留痕表（只增不改）
-- 不设 update_time / deleted_at：审计记录一旦写入即不可变。
-- biz_no 必须始终有值——它是事后「凭单号反查」的唯一入口（wms_sync_log 的 object_no 大量为空，就是反面教材）。
CREATE TABLE IF NOT EXISTS `wms_audit_log` (
  `id`           varchar(32)   NOT NULL COMMENT '主键',
  `biz_type`     varchar(32)   NOT NULL COMMENT '业务类型：工单/入库单/出库单/调拨单/盘点单/系统配置/主数据',
  `biz_no`       varchar(64)   NOT NULL COMMENT '业务单号（反查入口）；按行同步用「单号#序列#工序号」保证唯一',
  `biz_ref`      varchar(64)   DEFAULT NULL COMMENT '关联单号（如入库单对应的工单号），用于串起链路',
  `action`       varchar(32)   NOT NULL COMMENT '动作：创建/修改/审核/作废/同步落地/回写ERP/绑定/解绑',
  `channel`      varchar(32)   NOT NULL COMMENT '通道：界面操作/ERP同步/回写ERP/设备回调/定时任务',
  `before_value` mediumtext    DEFAULT NULL COMMENT '变更前关键字段 JSON（diff 用）',
  `after_value`  mediumtext    DEFAULT NULL COMMENT '变更后关键字段 JSON',
  `diff_fields`  varchar(1000) DEFAULT NULL COMMENT '发生变化的字段名列表',
  `operator`     varchar(100)  DEFAULT NULL COMMENT '操作人；系统/定时任务写 system',
  `ip`           varchar(64)   DEFAULT NULL COMMENT '来源 IP',
  `request_id`   varchar(64)   DEFAULT NULL COMMENT '同一次请求/同一批同步的串联 id',
  `result`       varchar(20)   NOT NULL COMMENT '结果：成功/失败',
  `error_msg`    varchar(500)  DEFAULT NULL COMMENT '失败原因（截断）',
  `cost_ms`      int           DEFAULT NULL COMMENT '耗时毫秒',
  `create_time`  datetime      NOT NULL COMMENT '发生时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wms_audit_biz` (`biz_no`, `biz_type`) USING BTREE,
  INDEX `idx_wms_audit_time` (`create_time`) USING BTREE,
  INDEX `idx_wms_audit_ref` (`biz_ref`) USING BTREE,
  INDEX `idx_wms_audit_channel` (`channel`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='WMS 审计留痕（只增不改）';

-- ------------------------------------------------ 2) 防篡改：禁止 UPDATE
-- 只挡 UPDATE，不挡 DELETE——保留期清理本身要删，挡了 DELETE 清理任务就跑不动。
-- （DELETE 由应用层不提供接口 + 清理任务独占来控制。）
DROP TRIGGER IF EXISTS `trg_wms_audit_no_update`;
CREATE TRIGGER `trg_wms_audit_no_update`
BEFORE UPDATE ON `wms_audit_log`
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'wms_audit_log 是审计留痕表，禁止修改';
END;

-- ------------------------------------------------ 3) 菜单
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000801','','审计留痕','/wms/audit','layouts/default/index',1,'','/wms/audit/list',0,NULL,'0',98.00,0,'ant-design:safety-certificate-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000802','1910000000000000801','操作日志','/wms/audit/list','wms/audit/index',1,'','',1,NULL,'0',1.00,0,'ant-design:audit-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 4) 按钮权限（只授审计员 + 管理员，由运维在角色管理处勾选）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000811','1910000000000000802','审计-查询',NULL,NULL,0,NULL,'',2,'wms:audit:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000812','1910000000000000802','审计-导出',NULL,NULL,0,NULL,'',2,'wms:audit:export','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 5) admin 角色授权（审计员角色由运维另行创建）
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000801','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000801',NULL,NOW(),'127.0.0.1'),
('1920000000000000802','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000802',NULL,NOW(),'127.0.0.1'),
('1920000000000000811','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000811',NULL,NOW(),'127.0.0.1'),
('1920000000000000812','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000812',NULL,NOW(),'127.0.0.1');

-- ------------------------------------------------ 6) 保留期配置（前端系统配置页可调，单位：天）
INSERT IGNORE INTO `wms_system_config` (`id`,`config_key`,`config_value`,`config_desc`,`deleted_at`,`create_by`,`create_time`)
VALUES ('1920000000000000821','audit.retention.days','365','审计日志保留天数（到期由定时任务删除；配置 0 或负数视为无效，不执行清理）',0,'admin',NOW());
