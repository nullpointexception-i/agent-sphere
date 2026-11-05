-- ============================================================
-- V122: session / instance / run 扩展（Super-Agent 三层）
-- ============================================================

ALTER TABLE agent_instance
    ADD COLUMN IF NOT EXISTS instance_role VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN IF NOT EXISTS system_managed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS session_type VARCHAR(32) NOT NULL DEFAULT 'CONSOLE',
    ADD COLUMN IF NOT EXISTS super_agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS thread_id BIGINT;

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS trigger_event_id BIGINT,
    ADD COLUMN IF NOT EXISTS batch_id BIGINT;

ALTER TABLE agent_llm_interaction_record
    ADD COLUMN IF NOT EXISTS super_agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS session_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS thread_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_instance_role
    ON agent_instance (instance_role, system_managed) WHERE delete_flag = 0;
CREATE INDEX IF NOT EXISTS idx_session_super_agent
    ON agent_session (super_agent_id, session_type, status) WHERE delete_flag = 0;
CREATE INDEX IF NOT EXISTS idx_session_thread
    ON agent_session (thread_id) WHERE delete_flag = 0;
CREATE INDEX IF NOT EXISTS idx_llm_record_super_agent
    ON agent_llm_interaction_record (super_agent_id, created_at) WHERE delete_flag = 0;
