-- Tasks 能力管理权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '任务管理', 'admin:tasks', 'MENU', id, 9, '目标驱动多轮任务（tasks）管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务查看', 'admin:tasks:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务创建', 'admin:tasks:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务更新', 'admin:tasks:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:update');

-- 授权给 ADMIN 与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:tasks', 'admin:tasks:read',
                 'admin:tasks:create', 'admin:tasks:update')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
