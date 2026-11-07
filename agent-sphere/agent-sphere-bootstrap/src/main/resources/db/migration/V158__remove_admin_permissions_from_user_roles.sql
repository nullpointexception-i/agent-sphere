-- ============================================================
-- V158: Ordinary users must not hold admin-console permissions
--
-- The USER role is the ordinary-member template. It keeps business
-- permissions only (document, model, instance, capability, im, super-agent)
-- and loses every admin:* permission, which would otherwise expose the
-- admin console and its user/role management pages.
-- ============================================================

DELETE FROM sys_role_permission rp
USING sys_role r, sys_permission p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code = 'USER'
  AND p.code LIKE 'admin:%';
