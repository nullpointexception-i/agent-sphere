-- ============================================================
-- V159: Remove the remaining admin menu root from USER roles
--
-- V158 removed every admin:* permission but the root menu permission uses
-- the bare code 'admin'. Ordinary users must not hold it either, otherwise
-- the admin console root stays attached to the USER role.
-- ============================================================

DELETE FROM sys_role_permission rp
USING sys_role r, sys_permission p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'USER'
  AND p.code = 'admin';
