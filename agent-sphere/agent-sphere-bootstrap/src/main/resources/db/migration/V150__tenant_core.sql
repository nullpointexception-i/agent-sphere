-- ============================================================
-- V150: Tenant core (platform tenant, default tenant, user assignments)
--
-- Model:
--   sys_tenant      tenant tree; id=1 is the platform tenant (only root)
--   sys_user_tenant user <-> tenant assignments with visibility and current tenant
--
-- Existing users/data are moved into the default tenant (id=2), which is a child
-- of the platform tenant. Existing ordinary users start with SELF_ONLY so the
-- current "only my own rows" behaviour is preserved until an admin changes it.
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    pid         BIGINT,
    sort_order  INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(500),
    delete_flag SMALLINT     NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_tenant_code
    ON sys_tenant (code) WHERE delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_sys_tenant_pid
    ON sys_tenant (pid);

CREATE TABLE IF NOT EXISTS sys_user_tenant (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    visibility  VARCHAR(20)  NOT NULL DEFAULT 'TENANT',
    is_current  BOOLEAN      NOT NULL DEFAULT FALSE,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(500),
    delete_flag SMALLINT     NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_user_tenant
    ON sys_user_tenant (user_id, tenant_id) WHERE delete_flag = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_current
    ON sys_user_tenant (user_id) WHERE is_current AND delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_user
    ON sys_user_tenant (user_id);

CREATE INDEX IF NOT EXISTS idx_sys_user_tenant_tenant
    ON sys_user_tenant (tenant_id);

-- Platform tenant (id=1) is the only root. Default tenant (id=2) owns all
-- existing business data and is mounted under the platform tenant.
INSERT INTO sys_tenant (id, code, name, pid, sort_order, status, remark)
VALUES
    (1, 'PLATFORM', 'Platform', NULL, 0, 'ACTIVE',
     'Platform tenant: only root, platform administrators and system objects'),
    (2, 'DEFAULT', 'Default', 1, 1, 'ACTIVE',
     'Default tenant: owner of all existing users and business data')
ON CONFLICT (id) DO NOTHING;

SELECT setval('sys_tenant_id_seq',
              (SELECT COALESCE(MAX(id), 1) FROM sys_tenant), true);

-- Backfill sys_user_tenant for every existing user.
INSERT INTO sys_user_tenant
    (user_id, tenant_id, visibility, is_current, status)
SELECT
    u.id,
    CASE WHEN u.super_admin = 'YES' THEN 1 ELSE 2 END,
    CASE WHEN u.super_admin = 'YES' THEN 'TENANT_SUBTREE' ELSE 'SELF_ONLY' END,
    TRUE,
    'ACTIVE'
FROM agent_user u
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user_tenant ut WHERE ut.user_id = u.id
);

-- Idempotent repair: ensure every user that has assignments has exactly one
-- current tenant. Do not invent an assignment for users that have none.
UPDATE sys_user_tenant ut
SET is_current = TRUE,
    updated_at = NOW()
WHERE ut.status = 'ACTIVE'
  AND ut.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_tenant x
      WHERE x.user_id = ut.user_id
        AND x.is_current
        AND x.delete_flag = 0
  );

SELECT setval('sys_user_tenant_id_seq',
              (SELECT COALESCE(MAX(id), 1) FROM sys_user_tenant), true);

-- Keep the legacy column as a compatibility hint only. It is no longer the
-- authoritative source for the current tenant.
UPDATE agent_user
SET tenant_id = CASE WHEN super_admin = 'YES' THEN 1 ELSE 2 END;

-- ------------------------------------------------------------
-- Tenant administration permissions
-- ------------------------------------------------------------
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant', 'admin:tenant', 'MENU', p.id, 5
FROM sys_permission p
WHERE p.code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant read', 'admin:tenant:read', 'BUTTON', p.id, 1
FROM sys_permission p
WHERE p.code = 'admin:tenant'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant create', 'admin:tenant:create', 'BUTTON', p.id, 2
FROM sys_permission p
WHERE p.code = 'admin:tenant'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant update', 'admin:tenant:update', 'BUTTON', p.id, 3
FROM sys_permission p
WHERE p.code = 'admin:tenant'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant delete', 'admin:tenant:delete', 'BUTTON', p.id, 4
FROM sys_permission p
WHERE p.code = 'admin:tenant'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant:delete');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Tenant assign', 'admin:tenant:assign', 'BUTTON', p.id, 5
FROM sys_permission p
WHERE p.code = 'admin:tenant'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tenant:assign');

-- Grant tenant administration to the existing platform administrator role.
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r
JOIN sys_permission p ON p.code IN (
    'admin:tenant',
    'admin:tenant:read',
    'admin:tenant:create',
    'admin:tenant:update',
    'admin:tenant:delete',
    'admin:tenant:assign'
)
WHERE r.code = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
