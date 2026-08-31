-- 身份源管理（SSO/OIDC）权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '身份源管理', 'admin:identity-provider', 'MENU', id, 7, 'SSO/OIDC 身份源管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源查看', 'admin:identity-provider:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源创建', 'admin:identity-provider:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源修改', 'admin:identity-provider:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源删除', 'admin:identity-provider:delete', 'BUTTON', id, 4
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:delete');

-- 授权给 ADMIN（V12 的全量授权是一次性快照，新增权限需显式补齐）与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:identity-provider', 'admin:identity-provider:read',
                 'admin:identity-provider:create', 'admin:identity-provider:update',
                 'admin:identity-provider:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
