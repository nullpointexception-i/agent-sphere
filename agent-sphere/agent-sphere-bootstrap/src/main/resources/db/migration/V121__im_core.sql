-- ============================================================
-- V121: 内置 IM 核心
-- ============================================================

CREATE TABLE IF NOT EXISTS im_conversation (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    type             VARCHAR(16) NOT NULL DEFAULT 'DM',
    title            VARCHAR(256),
    purpose          VARCHAR(512),
    owner_user_id    BIGINT,
    thread_id        BIGINT,
    gate_config      JSONB,
    last_seq         BIGINT      NOT NULL DEFAULT 0,
    last_message_id  BIGINT,
    last_message_at  TIMESTAMPTZ,
    status           VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark           VARCHAR(512),
    delete_flag      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_im_conversation_owner
    ON im_conversation (owner_user_id, last_message_at) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_im_conversation_thread
    ON im_conversation (thread_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS im_participant (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    conversation_id  BIGINT      NOT NULL,
    participant_type VARCHAR(16) NOT NULL,
    participant_id   BIGINT      NOT NULL,
    role             VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    display_name     VARCHAR(128),
    muted            BOOLEAN     NOT NULL DEFAULT FALSE,
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    remark           VARCHAR(512),
    delete_flag      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_participant
    ON im_participant (conversation_id, participant_type, participant_id)
    WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_im_participant_lookup
    ON im_participant (participant_type, participant_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS im_message (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    conversation_id BIGINT      NOT NULL,
    seq             BIGINT      NOT NULL,
    sender_type     VARCHAR(16) NOT NULL,
    sender_id       BIGINT,
    client_msg_id   VARCHAR(64),
    type            VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    content         JSONB,
    reply_to_id     BIGINT,
    mentions        JSONB,
    status          VARCHAR(16) NOT NULL DEFAULT 'SENT',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_message_seq
    ON im_message (conversation_id, seq) WHERE delete_flag = FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_im_message_client
    ON im_message (conversation_id, client_msg_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_im_message_page
    ON im_message (conversation_id, seq) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS im_read_cursor (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT,
    conversation_id BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    device_id      VARCHAR(64) NOT NULL DEFAULT 'default',
    last_read_seq  BIGINT NOT NULL DEFAULT 0,
    remark         VARCHAR(512),
    delete_flag    BOOLEAN NOT NULL DEFAULT FALSE,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_read_cursor
    ON im_read_cursor (conversation_id, user_id, device_id) WHERE delete_flag = FALSE;
