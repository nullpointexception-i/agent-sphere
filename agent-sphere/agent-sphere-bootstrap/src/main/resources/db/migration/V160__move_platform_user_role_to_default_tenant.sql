-- ============================================================
-- V160: Replace the platform USER grant with the default tenant USER role
--
-- Platform roles are only effective inside the platform tenant. Existing
-- ordinary users belong to the default tenant, so a platform USER grant is
-- ineffective and misleading. Grant the default tenant's USER role instead,
-- then remove the platform grant.
-- ============================================================

INSERT INTO sys_user_role (user_id, role_id, tenant_id, status, created_by, updated_by, created_at, updated_at)
SELECT DISTINCT ur.user_id, tr.id, tr.tenant_id, 'ACTIVE', 'system', 'system', NOW(), NOW()
FROM sys_user_role ur
JOIN sys_role pr ON pr.id = ur.role_id
JOIN sys_role tr ON tr.code = 'USER' AND tr.tenant_id = 2
WHERE pr.code = 'USER'
  AND pr.tenant_id IS NULL
  AND ur.tenant_id IS NULL
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
  AND ur.tenant_id IS NULL;
