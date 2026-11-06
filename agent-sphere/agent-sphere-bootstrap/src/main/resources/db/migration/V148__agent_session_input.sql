-- ============================================================
-- V148: Router input bridge (clarification / tool approval cards)
-- ============================================================
-- Router runs may park in AWAITING_USER waiting on ask_clarification or an L2
-- tool approval. Those requests never reached the IM DM (the events live in the
-- internal Router session timeline) and the user's next message started a new
-- run instead of resuming the parked one. `agent_session_input` is the durable
-- bridge state: the pending input plus the im_message card that asks the user.

CREATE TABLE agent_session_input (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL DEFAULT 0,
    super_agent_id   BIGINT      NOT NULL,
    thread_id        BIGINT      NOT NULL,
    session_id       BIGINT      NOT NULL,
    run_id           BIGINT      NOT NULL,
    input_type       VARCHAR(16) NOT NULL,                -- CLARIFICATION / APPROVAL
    input_id         VARCHAR(64) NOT NULL,                -- clarificationId / approvalId
    title            VARCHAR(500),
    description      TEXT,
    options          TEXT,                                -- options JSON passthrough
    multiple         BOOLEAN     NOT NULL DEFAULT FALSE,
    tool_calls_json  TEXT,                                -- approval calls JSON passthrough
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING / ANSWERED / CANCELLED
    card_outbound_id BIGINT,
    responded_by     VARCHAR(64),
    responded_at     TIMESTAMP,
    remark           VARCHAR(512),
    delete_flag      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64) NOT NULL DEFAULT '',
    updated_by       VARCHAR(64) NOT NULL DEFAULT '',
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One live pending input per clarification/approval id; re-fires are ignored.
CREATE UNIQUE INDEX uk_session_input_pending
    ON agent_session_input (input_type, input_id)
    WHERE status = 'PENDING' AND delete_flag = FALSE;

CREATE INDEX idx_session_input_thread
    ON agent_session_input (thread_id, status)
    WHERE delete_flag = FALSE;

CREATE INDEX idx_session_input_run
    ON agent_session_input (run_id)
    WHERE delete_flag = FALSE;
