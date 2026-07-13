INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色创建', 'admin:role:create', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:role';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色编辑', 'admin:role:update', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:role';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色删除', 'admin:role:delete', 'BUTTON', id, 3 FROM sys_permission WHERE code = 'admin:role';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code IN ('admin:role:create', 'admin:role:update', 'admin:role:delete')
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
