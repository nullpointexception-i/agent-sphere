-- Completions 能力管理权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT 'Completions 管理', 'admin:completions', 'MENU', id, 8, '单次 LLM 能力（completions）管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 查看', 'admin:completions:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 创建', 'admin:completions:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 修改', 'admin:completions:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 删除', 'admin:completions:delete', 'BUTTON', id, 4
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:delete');

-- 授权给 ADMIN 与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:completions', 'admin:completions:read',
                 'admin:completions:create', 'admin:completions:update',
                 'admin:completions:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
