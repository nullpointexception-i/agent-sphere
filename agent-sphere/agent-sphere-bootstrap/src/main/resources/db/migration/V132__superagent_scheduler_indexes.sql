-- ============================================================
-- V132: scheduler scan indexes
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_event_created
    ON agent_event (created_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_delivery_created
    ON agent_event_delivery (created_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_outbound_created
    ON agent_outbound_message (created_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_work_item_created
    ON agent_work_item (created_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_work_item_status_progress
    ON agent_work_item (status, last_progress_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_delivery_status_claimed
    ON agent_event_delivery (status, claimed_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_outbound_status_updated
    ON agent_outbound_message (status, updated_at) WHERE delete_flag = FALSE;

CREATE INDEX IF NOT EXISTS idx_work_item_dispatching_progress
    ON agent_work_item (last_progress_at)
    WHERE delete_flag = FALSE AND status = 'DISPATCHING';
