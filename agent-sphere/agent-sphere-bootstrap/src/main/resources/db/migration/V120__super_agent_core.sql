-- ============================================================
-- V120: Super-Agent core (Group Router / Agent Router / Worker)
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_super_agent (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    owner_user_id   BIGINT,
    agent_key       VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    router_instance_id BIGINT,
    router_session_id  BIGINT,
    worker_instance_id BIGINT,
    config          JSONB,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_super_agent_key
    ON agent_super_agent (agent_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_super_agent_owner
    ON agent_super_agent (tenant_id, owner_user_id, status) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_channel_binding (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    super_agent_id      BIGINT,
    channel_type        VARCHAR(32)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    credential_ref      VARCHAR(128),
    inbound_token_hash  VARCHAR(128),
    config              JSONB,
    status              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark              VARCHAR(512),
    delete_flag         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_channel_binding_name
    ON agent_channel_binding (channel_type, name) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_channel_binding_agent
    ON agent_channel_binding (super_agent_id, status) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_thread (
    id                      BIGSERIAL PRIMARY KEY,
    tenant_id               BIGINT,
    channel_binding_id      BIGINT,
    external_thread_id      VARCHAR(256),
    kind                    VARCHAR(32),
    title                   VARCHAR(512),
    group_router_session_id BIGINT,
    summary                 TEXT,
    event_seq               BIGINT      NOT NULL DEFAULT 0,
    state                   VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    meta                    JSONB,
    remark                  VARCHAR(512),
    delete_flag             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by              VARCHAR(64),
    updated_by              VARCHAR(64),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_thread_external
    ON agent_thread (channel_binding_id, external_thread_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_thread_router
    ON agent_thread (group_router_session_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_event (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    thread_id           BIGINT,
    seq                 BIGINT,
    kind                VARCHAR(48) NOT NULL,
    external_id         VARCHAR(256),
    dedupe_key          VARCHAR(256) NOT NULL,
    payload             JSONB,
    status              VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    group_decision      VARCHAR(32),
    group_reason        VARCHAR(512),
    group_processed_at  TIMESTAMPTZ,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    remark              VARCHAR(512),
    delete_flag         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_event_dedupe
    ON agent_event (dedupe_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_event_thread_seq
    ON agent_event (thread_id, seq) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_event_group_pending
    ON agent_event (group_processed_at) WHERE delete_flag = FALSE AND group_processed_at IS NULL;

CREATE TABLE IF NOT EXISTS agent_event_delivery (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT,
    event_id          BIGINT NOT NULL,
    super_agent_id    BIGINT NOT NULL,
    target_session_id BIGINT,
    decision          VARCHAR(32) NOT NULL,
    reason            VARCHAR(512),
    delivered_at      TIMESTAMPTZ,
    remark            VARCHAR(512),
    delete_flag       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_event_delivery
    ON agent_event_delivery (event_id, super_agent_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_event_delivery_agent
    ON agent_event_delivery (super_agent_id, delivered_at) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_thread_subscription (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    thread_id       BIGINT NOT NULL,
    super_agent_id  BIGINT NOT NULL,
    mode            VARCHAR(16) NOT NULL DEFAULT 'PASS_THROUGH',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_thread_subscription
    ON agent_thread_subscription (thread_id, super_agent_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_work_item (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT,
    owner_user_id     BIGINT,
    super_agent_id    BIGINT NOT NULL,
    thread_id         BIGINT,
    worker_session_id BIGINT,
    title             VARCHAR(256) NOT NULL,
    goal              TEXT,
    priority          INT         NOT NULL DEFAULT 100,
    status            VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    summary           TEXT,
    last_progress_at  TIMESTAMPTZ,
    remark            VARCHAR(512),
    delete_flag       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_work_item_queue
    ON agent_work_item (status, priority, created_at) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_work_item_agent
    ON agent_work_item (super_agent_id, status) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_work_item_worker
    ON agent_work_item (worker_session_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_outbound_message (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT,
    super_agent_id      BIGINT NOT NULL,
    thread_id           BIGINT,
    idempotency_key     VARCHAR(128) NOT NULL,
    payload             JSONB,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts            INT         NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    external_message_id VARCHAR(256),
    remark              VARCHAR(512),
    delete_flag         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by          VARCHAR(64),
    updated_by          VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_outbound_idempotency
    ON agent_outbound_message (idempotency_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_outbound_pending
    ON agent_outbound_message (status, next_retry_at) WHERE delete_flag = FALSE;
