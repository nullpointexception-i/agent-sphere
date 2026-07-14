CREATE TABLE IF NOT EXISTS agent_pending_clarification (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT       NOT NULL,
    run_id        BIGINT       NOT NULL,
    title         VARCHAR(255) NOT NULL,
    type          VARCHAR(32)  NOT NULL,
    options       TEXT,
    user_response TEXT,
    expires_at    TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_clarification_session ON agent_pending_clarification(session_id);
CREATE INDEX IF NOT EXISTS idx_clarification_run    ON agent_pending_clarification(run_id);
