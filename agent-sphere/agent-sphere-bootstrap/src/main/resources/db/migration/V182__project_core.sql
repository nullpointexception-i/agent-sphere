-- Project, member, requirement and requirement activity core tables.

CREATE TABLE IF NOT EXISTS pm_project (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT,
    project_key      VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      TEXT,
    owner_user_id    BIGINT       NOT NULL,
    visibility       VARCHAR(16)  NOT NULL DEFAULT 'TENANT',
    status           VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    start_date       DATE,
    end_date         DATE,
    requirement_seq  BIGINT       NOT NULL DEFAULT 0,
    config           JSONB,
    remark           VARCHAR(512),
    delete_flag      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_project_key
    ON pm_project (tenant_id, project_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_project_owner
    ON pm_project (tenant_id, owner_user_id, status) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_project_visibility
    ON pm_project (tenant_id, visibility, status) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS pm_project_member (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT,
    project_id   BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    member_role  VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    remark       VARCHAR(512),
    delete_flag  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_project_member
    ON pm_project_member (project_id, user_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_project_member_user
    ON pm_project_member (user_id, project_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS pm_requirement (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              BIGINT,
    project_id             BIGINT       NOT NULL,
    requirement_key        VARCHAR(64)  NOT NULL,
    title                  VARCHAR(256) NOT NULL,
    description            TEXT,
    status                 VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    priority               INT          NOT NULL DEFAULT 100,
    owner_user_id          BIGINT,
    assignee_user_id       BIGINT,
    due_date               DATE,
    source_conversation_id BIGINT,
    tags                   JSONB,
    remark                 VARCHAR(512),
    delete_flag            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by             VARCHAR(64),
    updated_by             VARCHAR(64),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_pm_requirement_key
    ON pm_requirement (project_id, requirement_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_requirement_project
    ON pm_requirement (project_id, status, priority) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_requirement_assignee
    ON pm_requirement (assignee_user_id, status) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_pm_requirement_updated
    ON pm_requirement (project_id, updated_at DESC) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS pm_requirement_activity (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    requirement_id  BIGINT      NOT NULL,
    actor_user_id   BIGINT,
    activity_type   VARCHAR(32) NOT NULL,
    from_value      VARCHAR(128),
    to_value        VARCHAR(128),
    detail          JSONB,
    remark          VARCHAR(512),
    delete_flag     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_pm_requirement_activity
    ON pm_requirement_activity (requirement_id, created_at DESC) WHERE delete_flag = FALSE;
