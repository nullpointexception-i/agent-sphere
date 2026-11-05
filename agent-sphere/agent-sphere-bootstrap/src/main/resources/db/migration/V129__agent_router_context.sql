-- ============================================================
-- V129: Agent Router run context and delivery binding
-- ============================================================

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS thread_id BIGINT,
    ADD COLUMN IF NOT EXISTS finish_status VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_run_thread
    ON agent_run (thread_id) WHERE delete_flag = 0;

ALTER TABLE agent_event_delivery
    ADD COLUMN IF NOT EXISTS router_run_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_delivery_router_run
    ON agent_event_delivery (router_run_id) WHERE delete_flag = FALSE;
