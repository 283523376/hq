-- =============================================================
-- DY-001 单据打印模块：快照表 + 菜单与权限
-- 2026-11-09
-- =============================================================

-- ------------------------------------------------ 1) 快照表：金蝶来源单据的离线打印底账
CREATE TABLE IF NOT EXISTS `wms_print_document` (
    `id`            VARCHAR(36)  NOT NULL COMMENT '主键',
    `bill_type`     VARCHAR(32)  NOT NULL COMMENT '打印单据类型码（PROFIT/LOSS/MISC_DELIVERY/MISC_INSTOCK/SUB_PICK/SALES_OUTSTOCK/SALES_RETURN）',
    `erp_bill_no`   VARCHAR(64)  NOT NULL COMMENT '金蝶单号',
    `payload`       LONGTEXT     NULL COMMENT '查询原文快照（金蝶分录行 JSON）',
    `snapshot_time` DATETIME     NULL COMMENT '快照时间（每次刷新覆盖）',
    `create_by`     VARCHAR(32)  NULL,
    `create_time`   DATETIME     NULL,
    `update_by`     VARCHAR(32)  NULL,
    `update_time`   DATETIME     NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_print_doc` (`bill_type`, `erp_bill_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '单据打印快照（DY-001）';

-- ------------------------------------------------ 2) 菜单：单据打印（一级 + 工作台页）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000801','','单据打印','/wms/print','layouts/default/index',1,'','/wms/print/workbench',0,NULL,'0',88.00,0,'ant-design:printer-outlined',0,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000802','1910000000000000801','打印工作台','/wms/print/workbench','wms/print/index',1,'','',1,NULL,'0',1.00,0,'ant-design:printer-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 3) 按钮权限
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000811','1910000000000000802','打印-查询',NULL,NULL,0,NULL,'',2,'wms:print:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ------------------------------------------------ 4) admin 角色授权
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000801','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000801',NULL,NOW(),'127.0.0.1'),
('1920000000000000802','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000802',NULL,NOW(),'127.0.0.1'),
('1920000000000000811','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000811',NULL,NOW(),'127.0.0.1');
