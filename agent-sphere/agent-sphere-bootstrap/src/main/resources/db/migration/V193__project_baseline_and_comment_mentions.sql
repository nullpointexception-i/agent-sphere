-- ============================================================
-- V193: project baseline batching and requirement-comment mentions
-- ============================================================

-- Insights are removed entirely. Historical rows are intentionally discarded.
DROP TABLE IF EXISTS pm_project_insight;

-- Analysis state for the incremental project context stream.
ALTER TABLE pm_project_context_event
    ADD COLUMN IF NOT EXISTS analysis_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS analysis_batch_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS analysis_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS analysis_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS analysis_next_retry_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS analysis_processed_at TIMESTAMP WITHOUT TIME ZONE;

-- Existing history predates batching; do not analyze the whole backlog on first deploy.
UPDATE pm_project_context_event
SET analysis_status = 'SKIPPED'
WHERE analysis_status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_pm_project_context_analysis
    ON pm_project_context_event (project_id, analysis_status, id)
    WHERE delete_flag = FALSE;

-- One row per canonical analysis batch.
CREATE TABLE IF NOT EXISTS pm_project_analysis_batch (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            BIGINT,
    project_id           BIGINT NOT NULL,
    batch_key            VARCHAR(64) NOT NULL,
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    event_count          INT NOT NULL DEFAULT 0,
    char_count           INT NOT NULL DEFAULT 0,
    canonical_session_id BIGINT,
    run_id               BIGINT,
    attempts             INT NOT NULL DEFAULT 0,
    next_retry_at        TIMESTAMP WITHOUT TIME ZONE,
    error_message        VARCHAR(2000),
    started_at           TIMESTAMP WITHOUT TIME ZONE,
    finished_at          TIMESTAMP WITHOUT TIME ZONE,
    remark               VARCHAR(512),
    delete_flag           BOOLEAN NOT NULL DEFAULT FALSE,
    created_by            VARCHAR(64),
    updated_by            VARCHAR(64),
    created_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_project_analysis_batch_key
    ON pm_project_analysis_batch (batch_key)
    WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_pm_project_analysis_batch_project
    ON pm_project_analysis_batch (project_id, status, id)
    WHERE delete_flag = FALSE;

-- Structured @ mentions for requirement comments.
CREATE TABLE IF NOT EXISTS pm_requirement_comment_mention (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    comment_id       BIGINT NOT NULL,
    mention_type     VARCHAR(16) NOT NULL,
    target_user_id   BIGINT,
    target_agent_id  BIGINT,
    status           VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    processed_at     TIMESTAMP WITHOUT TIME ZONE,
    reply_comment_id BIGINT,
    error_message    VARCHAR(2000),
    remark           VARCHAR(512),
    delete_flag      BOOLEAN NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_requirement_comment_mention_target
    ON pm_requirement_comment_mention (
        comment_id, mention_type, target_user_id, target_agent_id
    )
    WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_pm_requirement_comment_mention_status
    ON pm_requirement_comment_mention (status, id)
    WHERE delete_flag = FALSE;

-- Threaded replies and idempotency for assistant-generated comments.
ALTER TABLE pm_requirement_comment
    ADD COLUMN IF NOT EXISTS parent_comment_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_session_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_run_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_batch_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS action_key VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_pm_requirement_comment_parent
    ON pm_requirement_comment (parent_comment_id, created_at)
    WHERE delete_flag = FALSE AND parent_comment_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_requirement_comment_run_action
    ON pm_requirement_comment (source_run_id, action_key)
    WHERE delete_flag = FALSE AND source_run_id IS NOT NULL AND action_key IS NOT NULL;

-- Slot 26 used to be the insight tool. Re-enable it as the project comment tool for
-- every project router, and soft-delete any stale binding on another instance role.
UPDATE agent_instance_capability c
SET status = 'ENABLED',
    delete_flag = 0,
    updated_by = 'system',
    updated_at = NOW()
FROM agent_instance i
WHERE c.instance_id = i.id
  AND i.instance_role = 'PROJECT_ROUTER'
  AND c.capability_type = 'builtin'
  AND c.capability_id = 26;

UPDATE agent_instance_capability c
SET delete_flag = 1,
    updated_by = 'system',
    updated_at = NOW()
WHERE c.capability_type = 'builtin'
  AND c.capability_id = 26
  AND NOT EXISTS (
      SELECT 1 FROM agent_instance i
      WHERE i.id = c.instance_id
        AND i.instance_role = 'PROJECT_ROUTER'
  );

-- Bind the comment tool to project-router instances that never had slot 26.
INSERT INTO agent_instance_capability (
    tenant_id, instance_id, capability_type, capability_id, status,
    delete_flag, created_by, updated_by, created_at, updated_at
)
SELECT i.tenant_id, i.id, 'builtin', t.tool_id, 'ENABLED',
       0, 'system', 'system', NOW(), NOW()
FROM agent_instance i
CROSS JOIN (VALUES (26)) AS t(tool_id)
WHERE i.instance_role = 'PROJECT_ROUTER'
  AND i.delete_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_instance_capability c
      WHERE c.instance_id = i.id
        AND c.capability_type = 'builtin'
        AND c.capability_id = t.tool_id
        AND c.delete_flag = 0
  );
