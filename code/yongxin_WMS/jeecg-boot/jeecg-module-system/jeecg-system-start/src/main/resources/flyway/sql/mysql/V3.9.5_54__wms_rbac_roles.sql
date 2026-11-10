-- =============================================================
-- RBAC 五业务角色 + 租户治理（spec: docs/superpowers/specs/2026-11-10-yx-wms-权限租户角色优化-design.md §3）
-- 2026-11-10
--
-- 背景：库里 108 个 wms:% 权限点只挂在平台 admin 角色上（且缺 device:add/edit 两点），
-- 无业务角色，交付后无法按岗位分权。本脚本落地 5 个业务角色（spec §3.2 矩阵）并做
-- 租户治理（spec §3.1：admin 登录租户回归 0、清孤儿套餐直挂、演示租户停用）。
-- 零代码改动：前端 BACK 权限模式自动按角色渲染菜单与按钮。
--
-- 幂等：角色 INSERT IGNORE（固定 id）；授权行 id 一律为确定性拼接
--   '192' || SUBSTRING(permission_id, 4) || 角色位（admin角色='A'，901~905='1'~'5'）
--   —— sys_role_permission 没有 (role_id, permission_id) 唯一键，主键必须确定性，
--   重复执行 INSERT IGNORE 才不会插入重复行。治理 UPDATE/DELETE 均带条件，可重复执行。
-- =============================================================

-- ------------------------------------------------ 1) 五个业务角色（固定 id 段 1910000000000090xx，tenant_id=0）
INSERT IGNORE INTO sys_role (id, role_name, role_code, description, create_by, create_time, update_by, update_time, tenant_id) VALUES
('191000000000009001','WMS系统管理员','wms_admin','系统配置/主数据/用户角色维护','admin',NOW(),'admin',NOW(),0),
('191000000000009002','仓库主管','wms_manager','全业务+复核/差异确认/调整/ERP同步','admin',NOW(),'admin',NOW(),0),
('191000000000009003','仓管员','wms_operator','日常执行：出入库/移库/改制/不良品/生产过站/预警处理/打印','admin',NOW(),'admin',NOW(),0),
('191000000000009004','盘点员','wms_stocktake','盘点任务+实盘录入+库存查询+打印','admin',NOW(),'admin',NOW(),0),
('191000000000009005','查询角色','wms_viewer','只读：台账/追溯/审计/单据列表','admin',NOW(),'admin',NOW(),0);

-- ------------------------------------------------ 2) admin 角色补设备两权限点（V3.9.5_46 插菜单时漏授，spec §1.2 P2）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), 'A'), 'f6817f48af4fb3af11b9e8bf182f618b', p.id
FROM sys_permission p
WHERE p.perms IN ('wms:device:add','wms:device:edit');

-- ------------------------------------------------ 3) wms_admin：wms 全量按钮码（108）+ 全部 WMS 菜单子树（191 段）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), '1'), '191000000000009001', p.id
FROM sys_permission p
WHERE p.del_flag = 0 AND (p.perms LIKE 'wms:%' OR p.id LIKE '191%');

-- ------------------------------------------------ 4) wms_manager 按钮（88 = 108 − device4 − systemConfig2 − warehouse3
--                                                    − outbound执行6 − production过站2 − transfer执行3）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), '2'), '191000000000009002', p.id
FROM sys_permission p
WHERE p.del_flag = 0 AND p.perms LIKE 'wms:%'
  AND p.perms NOT LIKE 'wms:device:%'
  AND p.perms NOT LIKE 'wms:systemConfig:%'
  AND p.perms NOT LIKE 'wms:warehouse:%'
  AND p.perms NOT IN ('wms:outbound:add','wms:outbound:allocate','wms:outbound:confirm',
                      'wms:outbound:dispatch','wms:outbound:scan','wms:outbound:pick:cancel',
                      'wms:production:scan','wms:production:submit',
                      'wms:transfer:add','wms:transfer:dispatch','wms:transfer:execute');

-- ------------------------------------------------ 5) wms_operator 按钮（30）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), '3'), '191000000000009003', p.id
FROM sys_permission p
WHERE p.del_flag = 0 AND p.perms IN (
 'wms:conversion:cancel','wms:conversion:create','wms:conversion:execute','wms:conversion:view',
 'wms:defect:inbound','wms:defect:list','wms:defect:return','wms:defect:scrap',
 'wms:inbound:consigned',
 'wms:inventory:batch','wms:inventory:export','wms:inventory:ledger',
 'wms:print:list',
 'wms:outbound:add','wms:outbound:allocate','wms:outbound:confirm','wms:outbound:dispatch',
 'wms:outbound:list','wms:outbound:pick:cancel','wms:outbound:scan','wms:outbound:close',
 'wms:production:scan','wms:production:submit',
 'wms:transfer:add','wms:transfer:dispatch','wms:transfer:execute','wms:transfer:list',
 'wms:warning:handle','wms:warning:list','wms:warning:scan');

-- ------------------------------------------------ 6) wms_stocktake 按钮（6）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), '4'), '191000000000009004', p.id
FROM sys_permission p
WHERE p.del_flag = 0 AND p.perms IN (
 'wms:inventory:batch','wms:inventory:export','wms:inventory:ledger','wms:print:list',
 'wms:stocktake:list','wms:stocktake:record');

-- ------------------------------------------------ 7) wms_viewer 按钮（8）
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
SELECT CONCAT('192', SUBSTRING(p.id, 4), '5'), '191000000000009005', p.id
FROM sys_permission p
WHERE p.del_flag = 0 AND p.perms IN (
 'wms:audit:export','wms:audit:list',
 'wms:inventory:batch','wms:inventory:export','wms:inventory:ledger',
 'wms:outbound:list','wms:stocktake:list','wms:warning:list');

-- ------------------------------------------------ 8) 菜单子树（目录 id：0001基础 0101入库 0301出库 0401库存 0501盘点
--    0601预警 0621批次 0701查询 0801审计 0901不良品 0951上位系统 1001打印）
--    manager=全部 12 目录；operator=基础/入库/出库/库存/预警/批次/查询/不良品/打印；
--    stocktake=库存/盘点/批次/查询/打印；viewer=出库/库存/盘点/预警/批次/查询/审计。
--    演示菜单一个不挂 → 业务角色登录只见 WMS（spec §3.2 菜单可见性）。
--    子树只授 menu_type IN (0,1) 的菜单行；按钮一律走上面按矩阵的精确码授权，防止子树把
--    基础数据等目录的维护按钮泄漏给操作角色。
INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
WITH RECURSIVE tree AS (
  SELECT id FROM sys_permission WHERE del_flag = 0 AND id IN (
    '1910000000000000001','1910000000000000101','1910000000000000301','1910000000000000401',
    '1910000000000000501','1910000000000000601','1910000000000000621','1910000000000000701',
    '1910000000000000801','1910000000000000901','1910000000000000951','1910000000000001001')
  UNION ALL
  SELECT c.id FROM sys_permission c JOIN tree t ON c.parent_id = t.id AND c.del_flag = 0
) SELECT CONCAT('192', SUBSTRING(m.id, 4), '2'), '191000000000009002', m.id FROM tree t
  JOIN sys_permission m ON m.id = t.id AND m.menu_type IN (0, 1);

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
WITH RECURSIVE tree AS (
  SELECT id FROM sys_permission WHERE del_flag = 0 AND id IN (
    '1910000000000000001','1910000000000000101','1910000000000000301','1910000000000000401',
    '1910000000000000601','1910000000000000621','1910000000000000701',
    '1910000000000000901','1910000000000001001')
  UNION ALL
  SELECT c.id FROM sys_permission c JOIN tree t ON c.parent_id = t.id AND c.del_flag = 0
) SELECT CONCAT('192', SUBSTRING(m.id, 4), '3'), '191000000000009003', m.id FROM tree t
  JOIN sys_permission m ON m.id = t.id AND m.menu_type IN (0, 1);

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
WITH RECURSIVE tree AS (
  SELECT id FROM sys_permission WHERE del_flag = 0 AND id IN (
    '1910000000000000401','1910000000000000501','1910000000000000621',
    '1910000000000000701','1910000000000001001')
  UNION ALL
  SELECT c.id FROM sys_permission c JOIN tree t ON c.parent_id = t.id AND c.del_flag = 0
) SELECT CONCAT('192', SUBSTRING(m.id, 4), '4'), '191000000000009004', m.id FROM tree t
  JOIN sys_permission m ON m.id = t.id AND m.menu_type IN (0, 1);

INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
WITH RECURSIVE tree AS (
  SELECT id FROM sys_permission WHERE del_flag = 0 AND id IN (
    '1910000000000000301','1910000000000000401','1910000000000000501','1910000000000000601',
    '1910000000000000621','1910000000000000701','1910000000000000801')
  UNION ALL
  SELECT c.id FROM sys_permission c JOIN tree t ON c.parent_id = t.id AND c.del_flag = 0
) SELECT CONCAT('192', SUBSTRING(m.id, 4), '5'), '191000000000009005', m.id FROM tree t
  JOIN sys_permission m ON m.id = t.id AND m.menu_type IN (0, 1);

-- ------------------------------------------------ 9) 租户治理（spec §3.1）
-- admin 登录租户从演示租户 1000（北京国炬）回到默认租户 0
UPDATE sys_user SET login_tenant_id = 0 WHERE username = 'admin' AND login_tenant_id <> 0;
-- admin 补租户 0 关联（此前 sys_user_tenant 只有 1000）
INSERT IGNORE INTO sys_user_tenant (id, user_id, tenant_id, status, create_by, create_time)
SELECT REPLACE(UUID(), '-', ''), u.id, 0, '1', 'admin', NOW()
FROM sys_user u
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_user_tenant x WHERE x.user_id = u.id AND x.tenant_id = 0);
-- 清孤儿：套餐已被删但直挂行还在
DELETE pu FROM sys_tenant_pack_user pu
LEFT JOIN sys_tenant_pack p ON p.id = pu.pack_id
WHERE p.id IS NULL;
-- 角色授权行去重（历史迁移曾用随机 id 重复插入同一 (role, permission)，如 admin 的
-- wms:location:add/edit 各 2 行；运行时 DISTINCT 不受影响，但会误导审计计数）。
-- 确定性保留字典序最小 id，可重复执行。
DELETE r1 FROM sys_role_permission r1
JOIN sys_role_permission r2
  ON r1.role_id = r2.role_id AND r1.permission_id = r2.permission_id AND r1.id > r2.id;
-- 上游演示租户停用（保数据可回滚；若停用后登录页仍可选到它们，回退时再评估 del_flag）
UPDATE sys_tenant SET status = 0 WHERE id IN (1000, 1001) AND status <> 0;

-- =============================================================
-- 回滚段（手工执行，勿放进迁移）：
-- DELETE FROM sys_role_permission WHERE role_id LIKE '1910000000000090%';
-- DELETE FROM sys_role_permission WHERE role_id = 'f6817f48af4fb3af11b9e8bf182f618b'
--   AND permission_id IN (SELECT id FROM sys_permission WHERE perms IN ('wms:device:add','wms:device:edit'));
-- DELETE FROM sys_role WHERE id LIKE '1910000000000090%';
-- UPDATE sys_user SET login_tenant_id = 1000 WHERE username = 'admin';
-- DELETE FROM sys_user_tenant WHERE tenant_id = 0 AND user_id = (SELECT id FROM sys_user WHERE username = 'admin');
-- UPDATE sys_tenant SET status = 1 WHERE id IN (1000, 1001);
-- =============================================================
