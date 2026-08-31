-- ============================================================
-- agent_session_todo
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_session_todo (
    id             BIGSERIAL PRIMARY KEY,
    session_id     BIGINT NOT NULL,
    run_id         BIGINT,
    content        VARCHAR(500) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'pending',
    priority       VARCHAR(10) NOT NULL DEFAULT 'medium',
    sort_order     INT NOT NULL DEFAULT 0,
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_todo_session ON agent_session_todo(session_id);
CREATE INDEX IF NOT EXISTS idx_todo_session_run ON agent_session_todo(session_id, run_id);
