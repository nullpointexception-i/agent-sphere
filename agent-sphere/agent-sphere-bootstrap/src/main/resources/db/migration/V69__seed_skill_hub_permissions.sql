-- ============================================================
-- V69：skill hub 发布/安装按钮权限
-- 注意：V14 已删除 ADMIN 角色（仅剩 USER/DEMO），授权一律用 JOIN 写法：
-- 角色不存在时产生零行，不会违反 role_id 非空约束。另仿 V15 加 NOT EXISTS 保证幂等可重入。
-- 超管走 AuthContext.isSuperAdmin() 用户标记，不依赖角色行。
-- ============================================================
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Skill发布', 'capability:skill:publish', 'BUTTON', id, 14
FROM sys_permission WHERE code = 'capability'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'capability:skill:publish');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Skill安装', 'capability:skill:install', 'BUTTON', id, 15
FROM sys_permission WHERE code = 'capability'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'capability:skill:install');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER'
  AND p.code IN ('capability:skill:publish', 'capability:skill:install')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id);
