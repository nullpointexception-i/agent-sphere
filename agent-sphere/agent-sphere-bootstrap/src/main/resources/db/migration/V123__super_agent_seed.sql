-- ============================================================
-- V123: IM / Super-Agent permissions
-- ============================================================

INSERT INTO sys_permission (name, code, type, sort)
SELECT 'IM', 'im', 'MENU', 11
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Conversation Read', 'im:conversation:read', 'BUTTON', p.id, 1
FROM sys_permission p
WHERE p.code = 'im'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im:conversation:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Conversation Create', 'im:conversation:create', 'BUTTON', p.id, 2
FROM sys_permission p
WHERE p.code = 'im'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im:conversation:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Conversation Update', 'im:conversation:update', 'BUTTON', p.id, 3
FROM sys_permission p
WHERE p.code = 'im'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im:conversation:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Message Send', 'im:message:send', 'BUTTON', p.id, 4
FROM sys_permission p
WHERE p.code = 'im'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im:message:send');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Member Manage', 'im:member:manage', 'BUTTON', p.id, 5
FROM sys_permission p
WHERE p.code = 'im'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'im:member:manage');

INSERT INTO sys_permission (name, code, type, sort)
SELECT 'Super-Agent', 'super-agent', 'MENU', 12
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'super-agent');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Super-Agent Read', 'super-agent:read', 'BUTTON', p.id, 1
FROM sys_permission p
WHERE p.code = 'super-agent'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'super-agent:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Super-Agent Create', 'super-agent:create', 'BUTTON', p.id, 2
FROM sys_permission p
WHERE p.code = 'super-agent'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'super-agent:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Super-Agent Update', 'super-agent:update', 'BUTTON', p.id, 3
FROM sys_permission p
WHERE p.code = 'super-agent'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'super-agent:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Super-Agent Delete', 'super-agent:delete', 'BUTTON', p.id, 4
FROM sys_permission p
WHERE p.code = 'super-agent'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'super-agent:delete');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN (
      'im', 'im:conversation:read', 'im:conversation:create',
      'im:conversation:update', 'im:message:send', 'im:member:manage',
      'super-agent', 'super-agent:read', 'super-agent:create',
      'super-agent:update', 'super-agent:delete'
  )
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
