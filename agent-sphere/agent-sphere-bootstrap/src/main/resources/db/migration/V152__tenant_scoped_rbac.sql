-- ============================================================
-- V152: Tenant-scoped RBAC
--
-- sys_role.tenant_id     NULL = platform role, visible in every tenant
-- sys_user_role.tenant_id NULL = global grant, effective in every tenant
--
-- Existing rows stay NULL so current permissions are unchanged.
-- ============================================================

ALTER TABLE sys_role
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

ALTER TABLE sys_user_role
    ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

ALTER TABLE sys_role
    DROP CONSTRAINT IF EXISTS sys_role_code_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_tenant_code
    ON sys_role (COALESCE(tenant_id, 0), code);

CREATE INDEX IF NOT EXISTS idx_sys_role_tenant
    ON sys_role (tenant_id);

ALTER TABLE sys_user_role
    DROP CONSTRAINT IF EXISTS sys_user_role_user_id_role_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role_user_role_tenant
    ON sys_user_role (user_id, role_id, COALESCE(tenant_id, 0));

CREATE INDEX IF NOT EXISTS idx_sys_user_role_tenant
    ON sys_user_role (tenant_id);

-- Default tenant administrator role template. New tenants get the same role
-- created by TenantService; this covers the legacy/default tenant.
INSERT INTO sys_role (name, code, tenant_id, description, status)
SELECT 'Tenant Admin', 'TENANT_ADMIN', 2,
       'Administrator of the default tenant', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role WHERE tenant_id = 2 AND code = 'TENANT_ADMIN'
);

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT r.id, p.id, 'ACTIVE'
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'admin:user:read',
    'admin:user:assign-role',
    'admin:tenant:read',
    'admin:tenant:update',
    'admin:tenant:assign',
    'admin:role:read',
    'admin:role:create',
    'admin:role:update',
    'admin:role:delete',
    'admin:role:assign',
    'admin:audit-log:read'
)
WHERE r.tenant_id = 2
  AND r.code = 'TENANT_ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
