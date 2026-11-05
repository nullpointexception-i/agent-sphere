-- Per-user IM conversation preferences and a conversation-level pinned message.

ALTER TABLE im_conversation
    ADD COLUMN IF NOT EXISTS pinned_message_id BIGINT;

CREATE TABLE IF NOT EXISTS im_conversation_setting (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    conversation_id  BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    pinned           BOOLEAN      NOT NULL DEFAULT FALSE,
    muted            BOOLEAN      NOT NULL DEFAULT FALSE,
    remark           VARCHAR(256),
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    delete_flag      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_conversation_setting
    ON im_conversation_setting (conversation_id, user_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_im_conversation_setting_user
    ON im_conversation_setting (user_id, pinned) WHERE delete_flag = FALSE;
