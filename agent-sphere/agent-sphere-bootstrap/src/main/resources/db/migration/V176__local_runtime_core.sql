-- Local agent runtime registry, task queue and session mapping.
-- The desktop client authenticates with a device token and only sees its own runtime's tasks.

CREATE TABLE IF NOT EXISTS agent_local_runtime (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT,
    owner_user_id     BIGINT NOT NULL,
    name              VARCHAR(128) NOT NULL,
    runtime_kind      VARCHAR(32) NOT NULL,
    device_key        VARCHAR(64) NOT NULL,
    token_hash        VARCHAR(128) NOT NULL,
    token_prefix      VARCHAR(16),
    os                VARCHAR(32),
    arch              VARCHAR(32),
    app_version       VARCHAR(32),
    runtime_version   VARCHAR(32),
    capabilities      JSONB,
    config            JSONB,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    last_seen_at      TIMESTAMPTZ,
    last_ip           VARCHAR(64),
    remark            VARCHAR(512),
    delete_flag       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_runtime_device
    ON agent_local_runtime (device_key) WHERE delete_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_local_runtime_token
    ON agent_local_runtime (token_hash) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_local_runtime_owner
    ON agent_local_runtime (tenant_id, owner_user_id, status) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_local_session (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    super_agent_id  BIGINT NOT NULL,
    thread_id       BIGINT NOT NULL,
    runtime_id      BIGINT,
    session_ref     VARCHAR(256),
    last_seq        BIGINT DEFAULT 0,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_session
    ON agent_local_session (super_agent_id, thread_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_local_task (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT,
    super_agent_id     BIGINT NOT NULL,
    runtime_id         BIGINT NOT NULL,
    thread_id          BIGINT NOT NULL,
    status             VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    input_payload      JSONB,
    session_ref        VARCHAR(256),
    last_seq           BIGINT DEFAULT 0,
    lease_token        VARCHAR(64),
    lease_until        TIMESTAMPTZ,
    attempts           INT NOT NULL DEFAULT 0,
    deadline_at        TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    error_code         VARCHAR(64),
    error_message      VARCHAR(1000),
    reply_outbound_id  BIGINT,
    remark             VARCHAR(512),
    delete_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    created_by         VARCHAR(64),
    updated_by         VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_local_task_pick
    ON agent_local_task (runtime_id, status, created_at) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_local_task_thread
    ON agent_local_task (super_agent_id, thread_id, created_at) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_local_task_delivery (
    id           BIGSERIAL PRIMARY KEY,
    task_id      BIGINT NOT NULL,
    delivery_id  BIGINT NOT NULL,
    delete_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_task_delivery
    ON agent_local_task_delivery (task_id, delivery_id) WHERE delete_flag = FALSE;

ALTER TABLE agent_event_delivery
    ADD COLUMN IF NOT EXISTS target_task_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_event_delivery_task
    ON agent_event_delivery (target_task_id) WHERE target_task_id IS NOT NULL;
