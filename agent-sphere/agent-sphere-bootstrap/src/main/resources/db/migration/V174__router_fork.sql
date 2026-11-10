-- Soft forked Router sessions: a fork runs one delivery batch in parallel while the
-- parent session is busy, then merges its reply back in event-sequence order.

ALTER TABLE agent_session
    ADD COLUMN IF NOT EXISTS parent_session_id BIGINT,
    ADD COLUMN IF NOT EXISTS fork_kind VARCHAR(32),
    ADD COLUMN IF NOT EXISTS fork_state VARCHAR(32),
    ADD COLUMN IF NOT EXISTS next_merge_seq BIGINT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_agent_session_parent
    ON agent_session(parent_session_id);

CREATE TABLE IF NOT EXISTS agent_router_fork (
    id BIGSERIAL PRIMARY KEY,
    parent_session_id BIGINT NOT NULL,
    child_session_id BIGINT NOT NULL,
    child_run_id BIGINT,
    thread_id BIGINT NOT NULL,
    super_agent_id BIGINT NOT NULL,
    seq_from BIGINT NOT NULL,
    seq_to BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    remark VARCHAR(500),
    result_summary TEXT,
    reply_text TEXT,
    merge_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    merged_at TIMESTAMP,
    tenant_id BIGINT,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    updated_at TIMESTAMP,
    delete_flag BOOLEAN DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_router_fork_child
    ON agent_router_fork(child_session_id);

CREATE INDEX IF NOT EXISTS idx_agent_router_fork_merge
    ON agent_router_fork(parent_session_id, state, seq_from);

CREATE INDEX IF NOT EXISTS idx_agent_router_fork_running
    ON agent_router_fork(state, created_at);
