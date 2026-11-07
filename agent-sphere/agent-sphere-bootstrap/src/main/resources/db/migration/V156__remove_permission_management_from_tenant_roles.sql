-- ============================================================
-- V156: Tenant roles do not manage the permission catalogue
--
-- TENANT_ADMIN and USER keep their business/role/user permissions but lose
-- the whole admin:permission* group (menu + buttons). The role-permission
-- assignment modal is served by role-gated endpoints instead.
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
      OR p.code = 'admin:permission' OR p.code LIKE 'admin:permission:%'
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
      OR p.code = 'admin:permission' OR p.code LIKE 'admin:permission:%'
      OR p.code = 'admin:tenant' OR p.code LIKE 'admin:tenant:%'
  );
