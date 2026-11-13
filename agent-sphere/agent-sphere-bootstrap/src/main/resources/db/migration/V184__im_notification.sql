-- Generic user notifications (mentions, application runs) rendered with i18n keys.

CREATE TABLE IF NOT EXISTS im_notification (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    title_key   VARCHAR(160),
    body_key    VARCHAR(160),
    payload     JSONB,
    source_type VARCHAR(32),
    source_id   BIGINT,
    read_flag   BOOLEAN      NOT NULL DEFAULT FALSE,
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    delete_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_im_notification_user
    ON im_notification (user_id, read_flag, created_at DESC) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_im_notification_source
    ON im_notification (source_type, source_id) WHERE delete_flag = FALSE;
