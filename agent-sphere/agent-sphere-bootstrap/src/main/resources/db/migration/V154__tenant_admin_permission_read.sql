-- ============================================================
-- V154: Tenant administrators can read the tenant-scoped permission catalogue
--
-- The permission page now shows only the permissions effective in the current
-- tenant. Tenant administrators need admin:permission:read to open it and to
-- load the permission tree while assigning permissions to tenant roles.
-- ============================================================

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT r.id, p.id, 'ACTIVE'
FROM sys_role r
JOIN sys_permission p ON p.code = 'admin:permission:read'
WHERE r.code = 'TENANT_ADMIN'
  AND r.tenant_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
