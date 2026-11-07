-- ============================================================
-- V153: Tenant display names
--
-- Keep the stable codes (PLATFORM / DEFAULT / TENANT_ADMIN) but show
-- Chinese names in the default zh-CN UI.
-- ============================================================

UPDATE sys_tenant
SET name = '平台租户'
WHERE id = 1 AND code = 'PLATFORM';

UPDATE sys_tenant
SET name = '默认租户'
WHERE id = 2 AND code = 'DEFAULT';

UPDATE sys_role
SET name = '租户管理员'
WHERE code = 'TENANT_ADMIN' AND tenant_id = 2;
