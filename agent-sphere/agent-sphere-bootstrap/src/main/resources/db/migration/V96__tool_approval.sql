-- L2 tool approval: pending calls are attached to the existing clarification row;
-- SESSION/RUN grants are persisted here so the next calls skip the prompt.
ALTER TABLE agent_pending_clarification
    ADD COLUMN IF NOT EXISTS tool_calls_json TEXT;

CREATE TABLE IF NOT EXISTS agent_tool_approval_grant (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL,
    run_id      BIGINT,
    tool_ref    VARCHAR(300) NOT NULL,
    scope       VARCHAR(8) NOT NULL,
    approved_by VARCHAR(100),
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tool_approval_grant_session ON agent_tool_approval_grant(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_approval_grant_run ON agent_tool_approval_grant(run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_approval_grant_session
    ON agent_tool_approval_grant(session_id, tool_ref, scope)
    WHERE scope = 'SESSION' AND delete_flag = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_approval_grant_run
    ON agent_tool_approval_grant(run_id, tool_ref, scope)
    WHERE scope = 'RUN' AND delete_flag = 0;
