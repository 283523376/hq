-- =============================================================
-- RBAC 复审 Minor 修复（M1/M3/M4；M2 为纯文档修正不涉及迁移）
-- 2026-11-10
--
-- M1 授权行死引用清理：21 行（admin 15、vue3 角色 6）指向已删（del_flag=1）权限点。
--   运行时无害（queryByUserWithTenantId 有 p.del_flag=0 过滤），但污染审计计数。
--   全角色清理，幂等；将来恢复某权限点时由恢复迁移重新授权。
--
-- M3 operator 收回 系统配置页面(1910000000000000081)：子树授权带来的只读可见，
--   对仓管员无业务必要（复审 M3 建议）。manager 保留（主管只读可见，口径待客户确认可再收）。
--
-- M4 演示用户 jeecg 登录租户修正：login_tenant_id=1001 指向已停用租户导致无法登录；
--   修正为 0 并补租户 0 关联（镜像 admin 的治理方式，V54 §9）。
--   ceshi/zhangsan 本就 login_tenant_id=0，无需处理。
--
-- 幂等：DELETE 带条件；INSERT 有 NOT EXISTS 守卫；UPDATE 带 <> 目标值条件。可重复执行。
-- =============================================================

-- ------------------------------------------------ M1: 授权行死引用清理（全角色）
DELETE rp FROM sys_role_permission rp
JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.del_flag = 1;

-- ------------------------------------------------ M3: operator 收回 系统配置 页面
DELETE FROM sys_role_permission
WHERE role_id = '191000000000009003'
  AND permission_id = '1910000000000000081';

-- ------------------------------------------------ M4: jeecg 登录租户 1001(停用) → 0
UPDATE sys_user SET login_tenant_id = 0 WHERE username = 'jeecg' AND login_tenant_id <> 0;
INSERT IGNORE INTO sys_user_tenant (id, user_id, tenant_id, status, create_by, create_time)
SELECT REPLACE(UUID(), '-', ''), u.id, 0, '1', 'admin', NOW()
FROM sys_user u
WHERE u.username = 'jeecg'
  AND NOT EXISTS (SELECT 1 FROM sys_user_tenant x WHERE x.user_id = u.id AND x.tenant_id = 0);

-- ------------------------------------------------ 回滚段（手工执行，勿放进迁移）：
-- M1 死引用行不可精确恢复（原 id 已不可考）；如需恢复，按各权限点的原始迁移重新授权。
-- INSERT IGNORE INTO sys_role_permission (id, role_id, permission_id)
-- VALUES ('19200000000000000813','191000000000009003','1910000000000000081');
-- UPDATE sys_user SET login_tenant_id = 1001 WHERE username = 'jeecg';
-- DELETE FROM sys_user_tenant WHERE tenant_id = 0
--   AND user_id = (SELECT id FROM sys_user WHERE username = 'jeecg');
