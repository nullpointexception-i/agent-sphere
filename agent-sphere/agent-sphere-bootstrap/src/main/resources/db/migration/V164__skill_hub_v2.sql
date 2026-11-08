-- ============================================================
-- V164: Tenant-scoped Skill Hub (publish / follow / install)
--
-- The legacy hub was "every capability_skill with visibility = PUBLIC",
-- which is a cross-tenant list and still subject to per-user ownership
-- filtering. The hub is now an explicit tenant snapshot:
--
--   capability_skill_hub     published content, owned by one tenant
--   capability_skill_follow  user subscription, no local copy created
--
-- Readers see their own tenant's hub plus every ancestor tenant's hub, so
-- the platform tenant's hub acts as the platform-wide library.
--
-- Hub versions only increase: publishing/updating always bumps version by 1,
-- even when the author restores older content. There is no withdraw state.
-- ============================================================

CREATE TABLE IF NOT EXISTS capability_skill_hub (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    source_skill_id   BIGINT,
    publisher_user_id BIGINT,
    name              VARCHAR(200) NOT NULL DEFAULT '',
    description       TEXT,
    definition        JSONB,
    version           INT          NOT NULL DEFAULT 1,
    source_version    INT,
    install_count     INT          NOT NULL DEFAULT 0,
    follow_count      INT          NOT NULL DEFAULT 0,
    delete_flag       SMALLINT     NOT NULL DEFAULT 0,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_hub_source
    ON capability_skill_hub (tenant_id, source_skill_id)
    WHERE delete_flag = 0 AND source_skill_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_skill_hub_tenant
    ON capability_skill_hub (tenant_id);

CREATE TABLE IF NOT EXISTS capability_skill_follow (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT    NOT NULL,
    user_id      BIGINT    NOT NULL,
    hub_skill_id BIGINT    NOT NULL,
    delete_flag  SMALLINT  NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_follow
    ON capability_skill_follow (user_id, hub_skill_id) WHERE delete_flag = 0;

CREATE INDEX IF NOT EXISTS idx_skill_follow_hub
    ON capability_skill_follow (hub_skill_id);

-- Installed copies keep a link to the hub entry they follow.
ALTER TABLE capability_skill ADD COLUMN IF NOT EXISTS hub_skill_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_skill_hub_ref
    ON capability_skill (hub_skill_id);

-- Backfill: every legacy PUBLIC skill becomes one hub entry of its own tenant.
INSERT INTO capability_skill_hub
    (tenant_id, source_skill_id, publisher_user_id, name, description, definition,
     version, source_version, install_count, created_by, updated_by, created_at, updated_at)
SELECT s.tenant_id,
       s.id,
       (SELECT u.id FROM agent_user u
         WHERE u.username = s.created_by AND u.delete_flag = 0
         ORDER BY u.id LIMIT 1),
       s.name,
       s.description,
       s.definition,
       1,
       s.version,
       COALESCE(s.install_count, 0),
       s.created_by,
       s.created_by,
       s.created_at,
       s.updated_at
FROM capability_skill s
WHERE s.visibility = 'PUBLIC'
  AND s.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM capability_skill_hub h
      WHERE h.tenant_id = s.tenant_id
        AND h.source_skill_id = s.id
        AND h.delete_flag = 0
  );

-- Point existing installed copies at the hub entry of their origin.
UPDATE capability_skill c
SET hub_skill_id = h.id
FROM capability_skill_hub h
WHERE c.origin_skill_id = h.source_skill_id
  AND c.hub_skill_id IS NULL
  AND c.delete_flag = 0;
