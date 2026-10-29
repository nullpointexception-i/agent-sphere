-- L2 approval is a bottom-sheet interaction, not a clarification row.
CREATE TABLE IF NOT EXISTS agent_tool_approval (
    id              BIGSERIAL PRIMARY KEY,
    approval_id     VARCHAR(64) NOT NULL,
    session_id      BIGINT NOT NULL,
    run_id          BIGINT NOT NULL,
    tool_calls_json TEXT NOT NULL,
    decisions_json  TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    approved_by     VARCHAR(100),
    comment         TEXT,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag     SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_approval_id
    ON agent_tool_approval(approval_id) WHERE delete_flag = 0;
CREATE INDEX IF NOT EXISTS idx_tool_approval_session
    ON agent_tool_approval(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_approval_run
    ON agent_tool_approval(run_id);

ALTER TABLE agent_tool_approval_grant
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
