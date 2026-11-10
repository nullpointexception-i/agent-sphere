-- ============================================================
-- V172: retire system_managed, add tenant resource permissions
-- ============================================================
DROP INDEX IF EXISTS uk_model_route_system_tenant;
DROP INDEX IF EXISTS idx_instance_role;

ALTER TABLE agent_model_route DROP COLUMN IF EXISTS system_managed;
ALTER TABLE agent_instance DROP COLUMN IF EXISTS system_managed;

CREATE INDEX IF NOT EXISTS idx_instance_role
    ON agent_instance (instance_role) WHERE delete_flag = 0;

-- Tenant-level provider/instance permissions.
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant Provider', 'model:provider:tenant', 'BUTTON', p.id, 90
FROM sys_permission p
WHERE p.code = 'model'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'model:provider:tenant');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant Instance', 'instance:tenant', 'BUTTON', p.id, 90
FROM sys_permission p
WHERE p.code = 'instance'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'instance:tenant');

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT r.id, p.id, 'ACTIVE'
FROM sys_role r
JOIN sys_permission p ON p.code IN ('model:provider:tenant', 'instance:tenant')
WHERE r.code IN ('ADMIN', 'TENANT_ADMIN')
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Default tenant resource template: provider (empty API key), route, group router.
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description)
SELECT 'tenant', 'tenant.resource-template',
'[{"type":"model_provider","name":"Tenant Default Provider","baseUrl":"https://api.deepseek.com"},{"type":"model_route","provider":"Tenant Default Provider","modelName":"deepseek-flash","company":"deepseek","maxInputTokens":128000,"maxOutputTokens":4096,"isDefault":false},{"type":"instance","name":"Group Router","description":"Tenant group-level dispatcher","businessType":"SUPER_AGENT","instanceRole":"GROUP_ROUTER","route":"deepseek-flash"}]',
false, 'Default resources created together with a tenant (JSON array)'
WHERE NOT EXISTS (SELECT 1 FROM agent_system_config WHERE config_key = 'tenant.resource-template');
