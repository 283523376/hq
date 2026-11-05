-- ============================================================================
-- 永鑫WMS · 金蝶物料分组 → WMS 分类类型映射表（方案 A，2026-11-05 用户拍板）
--
-- 背景：映射原先塞在系统配置 kingdee.material-group-map 的字符串里
-- （格式 分组编码=类型;...），但 wms_system_config.config_value 是 varchar(500)，
-- 只能放约 20 条；而金蝶有 2039 个分组、需标 631 组才能覆盖 80% 物料。
-- 配置串装不下也无法维护，故改为独立映射表，由业务在页面上按分组认领。
--
-- 内容：
--   1) 新建 wms_material_group_map（分类类型四选一：原材料/半成品/成品/辅料）
--   2) 「物料分组映射」菜单（基础数据下）+ 4 个按钮权限 + admin 授权
--
-- 说明：
--   * 脚本可重复执行（CREATE TABLE IF NOT EXISTS + INSERT IGNORE）。
--   * 唯一索引把 deleted_at 纳入：被删除的分组编码不能永久占用（与
--     uk_wms_customer_code 同款，见 V3.9.5_50 的说明）。
--   * 本脚本**不搬迁**旧配置串里的既有映射（本机配置里已有 3 条）。
--     是否搬迁需业务确认后再单独执行，避免把测试口径写进正式表。
--   * 分类类型不建物理外键/枚举表：取值常量在 WmsConstants.CATEGORY_TYPE_*，
--     后端保存时整批校验（任一非法即整批拒绝）。
-- ============================================================================

-- ---------------------------------------------------------------- 1) 映射表
CREATE TABLE IF NOT EXISTS `wms_material_group_map` (
  `id`            varchar(32)  NOT NULL COMMENT '主键',
  `group_number`  varchar(64)  NOT NULL COMMENT '金蝶物料分组编码（BD_MATERIAL.FMaterialGroup.FNumber）',
  `group_name`    varchar(100) DEFAULT NULL COMMENT '金蝶物料分组名称（冗余，便于核对）',
  `category_type` varchar(20)  NOT NULL COMMENT '分类类型：原材料/半成品/成品/辅料',
  `remark`        varchar(200) DEFAULT NULL COMMENT '备注',
  `deleted_at`    bigint       NOT NULL DEFAULT 0 COMMENT '删除时间戳(epoch ms)：0=未删除，否则为删除时刻',
  `create_by`     varchar(50)  DEFAULT NULL COMMENT '创建人',
  `create_time`   datetime     DEFAULT NULL COMMENT '创建时间',
  `update_by`     varchar(50)  DEFAULT NULL COMMENT '更新人',
  `update_time`   datetime     DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_wms_mgm_group` (`group_number`, `deleted_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='金蝶物料分组→WMS分类类型映射';

-- ---------------------------------------------------------------- 2) 物料分组映射菜单
-- parent 1910000000000000001 = 「基础数据」目录（V3.9.5_2 建，V3.9.5_4 改过名）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000986','1910000000000000001','物料分组映射','/wms/materialGroupMap','wms/materialGroupMap/index',1,'','',1,NULL,'0',2.60,0,'ant-design:block-outlined',1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- 3) 按钮权限（menu_type=2）
INSERT IGNORE INTO `sys_permission` (`id`,`parent_id`,`name`,`url`,`component`,`is_route`,`component_name`,`redirect`,`menu_type`,`perms`,`perms_type`,`sort_no`,`always_show`,`icon`,`is_leaf`,`keep_alive`,`hidden`,`hide_tab`,`description`,`create_by`,`create_time`,`update_by`,`update_time`,`del_flag`,`rule_flag`,`status`,`internal_or_external`) VALUES
('1910000000000000987','1910000000000000986','物料分组映射-查询',NULL,NULL,0,NULL,'',2,'wms:materialGroupMap:list','1',1.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000988','1910000000000000986','物料分组映射-新增',NULL,NULL,0,NULL,'',2,'wms:materialGroupMap:create','1',2.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000989','1910000000000000986','物料分组映射-编辑',NULL,NULL,0,NULL,'',2,'wms:materialGroupMap:edit','1',3.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0),
('1910000000000000990','1910000000000000986','物料分组映射-删除',NULL,NULL,0,NULL,'',2,'wms:materialGroupMap:delete','1',4.00,0,NULL,1,0,0,0,NULL,'admin',NOW(),NULL,NULL,0,0,'1',0);

-- ---------------------------------------------------------------- 4) admin 角色授权
-- admin 角色 id 取自 V3.9.5_2__wms_material.sql
INSERT IGNORE INTO `sys_role_permission` (`id`,`role_id`,`permission_id`,`data_rule_ids`,`operate_date`,`operate_ip`) VALUES
('1920000000000000986','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000986',NULL,NOW(),'127.0.0.1'),
('1920000000000000987','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000987',NULL,NOW(),'127.0.0.1'),
('1920000000000000988','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000988',NULL,NOW(),'127.0.0.1'),
('1920000000000000989','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000989',NULL,NOW(),'127.0.0.1'),
('1920000000000000990','f6817f48af4fb3af11b9e8bf182f618b','1910000000000000990',NULL,NOW(),'127.0.0.1');
