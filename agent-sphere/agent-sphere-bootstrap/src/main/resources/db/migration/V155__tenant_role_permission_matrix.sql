-- ============================================================
-- V155: Rebuild TENANT_ADMIN and USER permission matrices
--
-- TENANT_ADMIN:
--   all permissions except the "System & Security" group
--   (settings / dictionary / audit log) and "Third-party Integrations"
--   (identity providers).
--
-- USER:
--   TENANT_ADMIN minus tenant management (admin:tenant*).
-- ============================================================

DELETE FROM sys_role_permission rp
USING sys_role r
WHERE rp.role_id = r.id
  AND (
      (r.code = 'TENANT_ADMIN' AND r.tenant_id IS NOT NULL)
      OR r.code = 'USER'
  );

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT r.id, p.id, 'ACTIVE'
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'TENANT_ADMIN'
  AND r.tenant_id IS NOT NULL
  AND NOT (
      p.code = 'admin:settings' OR p.code LIKE 'admin:settings:%'
      OR p.code = 'admin:dictionary' OR p.code LIKE 'admin:dictionary:%'
      OR p.code = 'admin:audit-log' OR p.code LIKE 'admin:audit-log:%'
      OR p.code = 'admin:identity-provider' OR p.code LIKE 'admin:identity-provider:%'
  );

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT r.id, p.id, 'ACTIVE'
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.code = 'USER'
  AND NOT (
      p.code = 'admin:settings' OR p.code LIKE 'admin:settings:%'
      OR p.code = 'admin:dictionary' OR p.code LIKE 'admin:dictionary:%'
      OR p.code = 'admin:audit-log' OR p.code LIKE 'admin:audit-log:%'
      OR p.code = 'admin:identity-provider' OR p.code LIKE 'admin:identity-provider:%'
      OR p.code = 'admin:tenant' OR p.code LIKE 'admin:tenant:%'
  );
