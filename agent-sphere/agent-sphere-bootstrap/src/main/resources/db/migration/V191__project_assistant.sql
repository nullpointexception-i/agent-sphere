-- ============================================================
-- V191: tenant-level project assistant (PROJECT_ROUTER)
-- ============================================================

-- 1. One PROJECT_ROUTER instance per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uk_instance_project_router_tenant
    ON agent_instance (tenant_id)
    WHERE instance_role = 'PROJECT_ROUTER' AND delete_flag = 0;

-- 2. Clone a project-router from each tenant's existing group-router.
INSERT INTO agent_instance (
    tenant_id, name, description, image, instance_role, owner_scope,
    model_route_id, allow_console_session, allow_widget_session,
    completion_protocol, status, delete_flag, created_by, updated_by, created_at, updated_at
)
SELECT g.tenant_id,
       'Project Assistant',
       'Tenant-level assistant for the project space',
       g.image,
       'PROJECT_ROUTER',
       'TENANT',
       g.model_route_id,
       TRUE,
       FALSE,
       g.completion_protocol,
       'ENABLED',
       0,
       'system', 'system', NOW(), NOW()
FROM agent_instance g
WHERE g.instance_role = 'GROUP_ROUTER'
  AND g.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_instance p
      WHERE p.tenant_id = g.tenant_id
        AND p.instance_role = 'PROJECT_ROUTER'
        AND p.delete_flag = 0
  );

-- 3. Append PROJECT_ROUTER to the tenant resource template.
UPDATE agent_system_config
SET config_value = (
        config_value::jsonb
        || jsonb_build_array(jsonb_build_object(
               'type', 'instance',
               'name', 'Project Assistant',
               'description', 'Tenant-level assistant for the project space',
               'businessType', 'SUPER_AGENT',
               'instanceRole', 'PROJECT_ROUTER',
               'route', COALESCE(
                   (SELECT e->>'modelName' FROM jsonb_array_elements(config_value::jsonb) e
                    WHERE e->>'type' = 'model_route' LIMIT 1),
                   'deepseek-flash'),
               'allowConsoleSession', TRUE,
               'allowWidgetSession', FALSE
           ))
    )::text,
    updated_at = NOW()
WHERE config_key = 'tenant.resource-template'
  AND delete_flag = 0
  AND config_value IS NOT NULL
  AND NOT (config_value::jsonb @> '[{"instanceRole":"PROJECT_ROUTER"}]'::jsonb);

-- 4. Bind the four project tools (23-26) to every project-router.
INSERT INTO agent_instance_capability (
    instance_id, capability_type, capability_id, status, tenant_id,
    delete_flag, created_by, updated_by, created_at, updated_at
)
SELECT i.id, 'builtin', t.tool_id, 'ENABLED', i.tenant_id,
       0, 'system', 'system', NOW(), NOW()
FROM agent_instance i
CROSS JOIN (VALUES (23), (24), (25), (26)) AS t(tool_id)
WHERE i.instance_role = 'PROJECT_ROUTER'
  AND i.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_instance_capability c
      WHERE c.instance_id = i.id
        AND c.capability_type = 'builtin'
        AND c.capability_id = t.tool_id
        AND c.delete_flag = 0
  );

-- 5. Project-level insights.
CREATE TABLE IF NOT EXISTS pm_project_insight (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT,
    project_id     BIGINT NOT NULL,
    requirement_id BIGINT,
    insight_type   VARCHAR(32)  NOT NULL DEFAULT 'SUMMARY',
    severity       VARCHAR(16)  NOT NULL DEFAULT 'INFO',
    title          VARCHAR(255),
    content        TEXT         NOT NULL,
    source         VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    source_run_id  BIGINT,
    status         VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark         VARCHAR(512),
    delete_flag    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pm_project_insight_project
    ON pm_project_insight (project_id, created_at DESC) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_pm_project_insight_requirement
    ON pm_project_insight (requirement_id)
    WHERE delete_flag = FALSE AND requirement_id IS NOT NULL;

-- 5b. Shared project context log. Every project/requirement/comment/assistant write
-- appends one normalized line; PROJECT_ROUTER sessions inject the latest lines as
-- shared context so the canonical session and all user forks see the same project state.
CREATE TABLE IF NOT EXISTS pm_project_context_event (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    project_id          BIGINT NOT NULL,
    requirement_id      BIGINT,
    event_type          VARCHAR(32) NOT NULL,
    summary             TEXT        NOT NULL,
    source_session_id   BIGINT,
    canonical_session_id BIGINT,
    actor_user_id       BIGINT,
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark              VARCHAR(512),
    delete_flag         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pm_project_context_project
    ON pm_project_context_event (project_id, created_at DESC) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_pm_project_context_requirement
    ON pm_project_context_event (requirement_id, created_at DESC)
    WHERE delete_flag = FALSE AND requirement_id IS NOT NULL;

-- 5c. Session registry: binds each canonical/fork PROJECT_ROUTER session to its project,
-- requester, and canonical parent without relying on a jsonb session column.
CREATE TABLE IF NOT EXISTS pm_project_assistant_session (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    project_id          BIGINT NOT NULL,
    session_id          BIGINT NOT NULL,
    canonical_session_id BIGINT,
    requester_user_id   BIGINT,
    session_role        VARCHAR(16) NOT NULL DEFAULT 'FORK',
    status              VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark              VARCHAR(512),
    delete_flag         BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_project_assistant_session
    ON pm_project_assistant_session (session_id) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_pm_project_assistant_session_project
    ON pm_project_assistant_session (project_id, session_role) WHERE delete_flag = FALSE;

-- 5d. Project assistant settings (auto analysis), kept out of the jsonb project config.
CREATE TABLE IF NOT EXISTS pm_project_assistant_config (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT,
    project_id   BIGINT NOT NULL,
    auto_analyze BOOLEAN NOT NULL DEFAULT FALSE,
    status       VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(512),
    delete_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    created_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_project_assistant_config
    ON pm_project_assistant_config (project_id) WHERE delete_flag = FALSE;

-- 6. Agent authors for requirement comments.
ALTER TABLE pm_requirement_comment
    ALTER COLUMN user_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS author_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN IF NOT EXISTS author_name VARCHAR(64);

-- 7. Agent actors for requirement activity.
ALTER TABLE pm_requirement_activity
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN IF NOT EXISTS actor_name VARCHAR(64);

-- 8. Targeted clarification for human questions.
ALTER TABLE agent_pending_clarification
    ADD COLUMN IF NOT EXISTS target_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS answered_by BIGINT;

CREATE INDEX IF NOT EXISTS idx_clarification_target
    ON agent_pending_clarification (target_user_id, expires_at)
    WHERE delete_flag = 0;

-- 9. One project thread per project.
CREATE UNIQUE INDEX IF NOT EXISTS uk_thread_project_external
    ON agent_thread (tenant_id, external_thread_id)
    WHERE delete_flag = FALSE AND kind = 'PROJECT';

-- 10. Backfill owner membership: the owner is always a member.
INSERT INTO pm_project_member (
    tenant_id, project_id, user_id, member_role, joined_at,
    delete_flag, created_by, updated_by, created_at, updated_at
)
SELECT p.tenant_id, p.id, p.owner_user_id, 'OWNER', NOW(),
       FALSE, 'system', 'system', NOW(), NOW()
FROM pm_project p
WHERE p.delete_flag = FALSE
  AND NOT EXISTS (
      SELECT 1 FROM pm_project_member m
      WHERE m.project_id = p.id
        AND m.user_id = p.owner_user_id
        AND m.delete_flag = FALSE
  );

-- 11. Retire legacy entity-level assistant bindings.
UPDATE agent_entity_binding
SET delete_flag = TRUE, updated_by = 'system', updated_at = NOW()
WHERE entity_type IN ('PROJECT', 'REQUIREMENT')
  AND delete_flag = FALSE;

-- 12. Session lookup index for project assistant sessions.
CREATE INDEX IF NOT EXISTS idx_session_project_router
    ON agent_session (thread_id, created_by, session_type, status)
    WHERE delete_flag = 0 AND session_type = 'PROJECT_ROUTER';
