-- Remove ADMIN role, keep only USER and DEMO
DELETE FROM sys_role_permission WHERE role_id = (SELECT id FROM sys_role WHERE code = 'ADMIN');
DELETE FROM sys_user_role WHERE role_id = (SELECT id FROM sys_role WHERE code = 'ADMIN');
DELETE FROM sys_role WHERE code = 'ADMIN';
