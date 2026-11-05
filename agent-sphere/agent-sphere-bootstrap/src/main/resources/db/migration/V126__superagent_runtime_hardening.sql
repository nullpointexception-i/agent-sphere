-- ============================================================
-- V126: Super-Agent runtime hardening
-- ============================================================

ALTER TABLE agent_event
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_event_retry
    ON agent_event (next_retry_at) WHERE delete_flag = FALSE AND group_processed_at IS NULL;

ALTER TABLE agent_event_delivery
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_delivery_pending
    ON agent_event_delivery (status, next_retry_at)
    WHERE delete_flag = FALSE;

ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS work_item_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_session_work_item
    ON agent_session (work_item_id) WHERE delete_flag = 0;

ALTER TABLE agent_work_item
    ADD COLUMN IF NOT EXISTS blocked_reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS speculation_key VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_work_item_speculation
    ON agent_work_item (speculation_key) WHERE delete_flag = FALSE;
