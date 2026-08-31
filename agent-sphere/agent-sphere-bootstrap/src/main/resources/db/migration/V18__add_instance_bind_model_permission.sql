INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '实例绑定模型', 'instance:bind-model', 'BUTTON', id, 7 FROM sys_permission WHERE code = 'instance';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code = 'instance:bind-model'
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
