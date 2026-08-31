-- Add sub-menu permissions under admin
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户管理', 'admin:user', 'MENU', id, 1 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色管理', 'admin:role', 'MENU', id, 2 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限管理', 'admin:permission', 'MENU', id, 3 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '系统配置', 'admin:settings', 'MENU', id, 4 FROM sys_permission WHERE code = 'admin';

-- Move existing BUTTONs under correct sub-menu
UPDATE sys_permission SET parent_id = (SELECT id FROM sys_permission WHERE code = 'admin:role')
WHERE code IN ('admin:role:read', 'admin:role:assign');
UPDATE sys_permission SET parent_id = (SELECT id FROM sys_permission WHERE code = 'admin:settings')
WHERE code IN ('admin:settings:read', 'admin:settings:update', 'admin:settings:regenerate-aes');

-- Add missing BUTTON permissions under admin:user and admin:permission
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户查看', 'admin:user:read', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:user';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户分配角色', 'admin:user:assign-role', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:user';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限查看', 'admin:permission:read', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限创建', 'admin:permission:create', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限编辑', 'admin:permission:update', 'BUTTON', id, 3 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限删除', 'admin:permission:delete', 'BUTTON', id, 4 FROM sys_permission WHERE code = 'admin:permission';

-- Grant new permissions to USER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER'
  AND p.code IN ('admin:user:read', 'admin:permission:read', 'admin:permission:create', 'admin:permission:update', 'admin:permission:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
