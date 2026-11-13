-- Requirement schedule and member comments.
ALTER TABLE pm_requirement ADD COLUMN IF NOT EXISTS start_date DATE;

CREATE TABLE IF NOT EXISTS pm_requirement_comment (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    requirement_id BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    content        TEXT         NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark         VARCHAR(512),
    delete_flag    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pm_requirement_comment_requirement
    ON pm_requirement_comment (requirement_id, created_at) WHERE delete_flag = FALSE;
