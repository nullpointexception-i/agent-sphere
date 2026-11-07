-- ============================================================
-- V157: Tenant-scoped USER role template
--
-- Platform roles are now effective only in the platform tenant. Every normal
-- tenant needs its own USER role for ordinary members. Existing users are not
-- assigned automatically: an administrator must assign the tenant role.
-- ============================================================

INSERT INTO sys_role (name, code, tenant_id, description, status)
SELECT '普通用户', 'USER', t.id, 'Tenant ordinary user', 'ACTIVE'
FROM sys_tenant t
WHERE t.id <> 1
  AND t.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_role r WHERE r.tenant_id = t.id AND r.code = 'USER'
  );

INSERT INTO sys_role_permission (role_id, permission_id, status)
SELECT tr.id, rp.permission_id, 'ACTIVE'
FROM sys_role tr
JOIN sys_role gr ON gr.code = 'USER' AND gr.tenant_id IS NULL
JOIN sys_role_permission rp ON rp.role_id = gr.id
WHERE tr.code = 'USER'
  AND tr.tenant_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission x
      WHERE x.role_id = tr.id AND x.permission_id = rp.permission_id
  );
