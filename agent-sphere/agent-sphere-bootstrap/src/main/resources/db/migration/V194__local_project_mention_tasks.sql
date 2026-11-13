-- ============================================================
-- V194: local project-mention tasks and mention retry state
-- ============================================================

-- Link a local task to the business operation that produced it.
ALTER TABLE agent_local_task
    ADD COLUMN IF NOT EXISTS business_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS business_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_agent_local_task_business
    ON agent_local_task (business_type, business_id)
    WHERE delete_flag = FALSE AND business_type IS NOT NULL;

-- One active local task per business operation. A failed task may be retried with a new row.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_local_task_business_active
    ON agent_local_task (business_type, business_id)
    WHERE delete_flag = FALSE
      AND business_type IS NOT NULL
      AND business_id IS NOT NULL
      AND status IN ('PENDING', 'RUNNING');

-- Retry state for mention delivery failures.
ALTER TABLE pm_requirement_comment_mention
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMP WITHOUT TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_pm_requirement_comment_mention_retry
    ON pm_requirement_comment_mention (status, next_retry_at, id)
    WHERE delete_flag = FALSE;

-- Avatar replies are written without a run id; make their action key unique.
CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_requirement_comment_mention_reply
    ON pm_requirement_comment (action_key)
    WHERE delete_flag = FALSE
      AND action_key LIKE 'mention-reply:%';
