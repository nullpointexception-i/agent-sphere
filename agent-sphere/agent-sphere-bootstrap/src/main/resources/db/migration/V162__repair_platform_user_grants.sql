-- ============================================================
-- V162: Repair platform USER grants created after V160
--
-- Self-registration used to pick the first USER role by id, which is the
-- platform role (tenant_id NULL). That grant is ineffective in the default
-- tenant and also got tenant_id = 0 from the audit fill. Replace it with the
-- USER role of the user's current tenant (non-platform tenants only).
-- ============================================================

INSERT INTO sys_user_role (user_id, role_id, tenant_id, status, created_by, updated_by, created_at, updated_at)
SELECT DISTINCT ur.user_id, tr.id, tr.tenant_id, 'ACTIVE', 'system', 'system', NOW(), NOW()
FROM sys_user_role ur
JOIN sys_role pr ON pr.id = ur.role_id
JOIN sys_user_tenant ut ON ut.user_id = ur.user_id
    AND ut.is_current = true
    AND ut.delete_flag = 0
    AND ut.status = 'ACTIVE'
    AND ut.tenant_id <> 1
JOIN sys_role tr ON tr.code = 'USER' AND tr.tenant_id = ut.tenant_id
WHERE pr.code = 'USER'
  AND pr.tenant_id IS NULL
  AND (ur.tenant_id IS NULL OR ur.tenant_id = 0)
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role x
      WHERE x.user_id = ur.user_id
        AND x.role_id = tr.id
        AND COALESCE(x.tenant_id, 0) = COALESCE(tr.tenant_id, 0)
  );

DELETE FROM sys_user_role ur
USING sys_role pr
WHERE ur.role_id = pr.id
  AND pr.code = 'USER'
  AND pr.tenant_id IS NULL
  AND (ur.tenant_id IS NULL OR ur.tenant_id = 0)
  AND EXISTS (
      SELECT 1 FROM sys_user_tenant ut
      WHERE ut.user_id = ur.user_id
        AND ut.is_current = true
        AND ut.delete_flag = 0
        AND ut.status = 'ACTIVE'
        AND ut.tenant_id <> 1
  );
