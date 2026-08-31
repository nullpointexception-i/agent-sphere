-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '审计日志', 'admin:audit-log', 'MENU', id, 5, '操作审计日志'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:audit-log');

-- BUTTON
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '审计日志查看', 'admin:audit-log:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:audit-log'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:audit-log:read');

-- Grant to USER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code IN ('admin:audit-log', 'admin:audit-log:read')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
