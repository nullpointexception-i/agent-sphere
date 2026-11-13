-- Application, scene, run, member, grant, i18n, entity binding and IM/work-item extensions.

CREATE TABLE IF NOT EXISTS biz_application (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    app_key         VARCHAR(64)  NOT NULL,
    name            VARCHAR(128),
    name_key        VARCHAR(160),
    description     VARCHAR(512),
    description_key VARCHAR(160),
    default_locale  VARCHAR(16)  NOT NULL DEFAULT 'zh-CN',
    icon            VARCHAR(256),
    app_type        VARCHAR(16)  NOT NULL DEFAULT 'CUSTOM',
    entry_mode      VARCHAR(16)  NOT NULL DEFAULT 'WORKBENCH',
    entry_key       VARCHAR(64),
    visibility      VARCHAR(16)  NOT NULL DEFAULT 'TENANT',
    owner_user_id   BIGINT,
    sort_order      INT          NOT NULL DEFAULT 100,
    config          JSONB,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_application_key
    ON biz_application (tenant_id, app_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_owner
    ON biz_application (tenant_id, owner_user_id, status) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_type
    ON biz_application (tenant_id, app_type, sort_order) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_name_key
    ON biz_application (name_key) WHERE delete_flag = FALSE AND name_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS biz_application_scene (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    application_id  BIGINT       NOT NULL,
    scene_key       VARCHAR(64)  NOT NULL,
    name            VARCHAR(128),
    name_key        VARCHAR(160),
    description     VARCHAR(512),
    description_key VARCHAR(160),
    default_locale  VARCHAR(16)  NOT NULL DEFAULT 'zh-CN',
    scene_type      VARCHAR(16)  NOT NULL,
    config          JSONB,
    sort_order      INT          NOT NULL DEFAULT 100,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_application_scene_key
    ON biz_application_scene (application_id, scene_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_scene_app
    ON biz_application_scene (application_id, sort_order) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_scene_name_key
    ON biz_application_scene (name_key) WHERE delete_flag = FALSE AND name_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS biz_application_run (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT,
    application_id    BIGINT       NOT NULL,
    scene_id          BIGINT       NOT NULL,
    trigger_type      VARCHAR(16)  NOT NULL,
    trigger_key       VARCHAR(128),
    idempotency_key   VARCHAR(160) NOT NULL,
    requester_user_id BIGINT,
    work_item_id      BIGINT,
    thread_id         BIGINT,
    run_mode          VARCHAR(16)  NOT NULL DEFAULT 'READ',
    output_locale     VARCHAR(16),
    status            VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    input             JSONB,
    output            JSONB,
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    remark            VARCHAR(512),
    delete_flag       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_application_run_idem
    ON biz_application_run (idempotency_key) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_run_app
    ON biz_application_run (application_id, created_at DESC) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_run_scene
    ON biz_application_run (scene_id, created_at DESC) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_run_status
    ON biz_application_run (status, created_at) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_run_work_item
    ON biz_application_run (work_item_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS biz_application_grant (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    application_id  BIGINT      NOT NULL,
    scene_id        BIGINT,
    grantee_user_id BIGINT      NOT NULL,
    run_mode        VARCHAR(16) NOT NULL DEFAULT 'WRITE',
    scope           JSONB,
    expires_at      TIMESTAMPTZ,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_application_grant
    ON biz_application_grant (application_id, COALESCE(scene_id, 0), grantee_user_id)
    WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS biz_application_member (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT,
    application_id  BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    member_role     VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    remark          VARCHAR(512),
    delete_flag     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by      VARCHAR(64),
    updated_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_application_member
    ON biz_application_member (application_id, user_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_biz_application_member_user
    ON biz_application_member (user_id, application_id) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS sys_i18n_text (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    message_key  VARCHAR(160) NOT NULL,
    locale       VARCHAR(16)  NOT NULL,
    text         TEXT         NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(512),
    delete_flag  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_i18n_text
    ON sys_i18n_text (tenant_id, message_key, locale) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_sys_i18n_text_lookup
    ON sys_i18n_text (message_key, locale, status) WHERE delete_flag = FALSE;

ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS default_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN';
ALTER TABLE sys_tenant ADD COLUMN IF NOT EXISTS timezone       VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';

CREATE TABLE IF NOT EXISTS sys_user_locale (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    locale      VARCHAR(16)  NOT NULL DEFAULT 'zh-CN',
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(512),
    delete_flag BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  VARCHAR(64),
    updated_by  VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_locale
    ON sys_user_locale (user_id, tenant_id) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_sys_user_locale_tenant
    ON sys_user_locale (tenant_id, locale) WHERE delete_flag = FALSE;

CREATE TABLE IF NOT EXISTS agent_entity_binding (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT,
    entity_type       VARCHAR(32) NOT NULL,
    entity_id         BIGINT      NOT NULL,
    super_agent_id    BIGINT      NOT NULL,
    binding_role      VARCHAR(16) NOT NULL DEFAULT 'ASSISTANT',
    capability_scope  JSONB,
    status            VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    remark            VARCHAR(512),
    delete_flag       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by        VARCHAR(64),
    updated_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_entity_binding
    ON agent_entity_binding (tenant_id, entity_type, entity_id, super_agent_id)
    WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_agent_entity_binding_entity
    ON agent_entity_binding (entity_type, entity_id, status) WHERE delete_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_agent_entity_binding_agent
    ON agent_entity_binding (super_agent_id, status) WHERE delete_flag = FALSE;

ALTER TABLE im_conversation ADD COLUMN IF NOT EXISTS biz_type    VARCHAR(32);
ALTER TABLE im_conversation ADD COLUMN IF NOT EXISTS biz_id      BIGINT;
ALTER TABLE im_conversation ADD COLUMN IF NOT EXISTS biz_purpose VARCHAR(32);
ALTER TABLE im_conversation ADD COLUMN IF NOT EXISTS biz_key     VARCHAR(128);
ALTER TABLE im_conversation ADD COLUMN IF NOT EXISTS locale      VARCHAR(16);

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_conversation_biz
    ON im_conversation (
        tenant_id,
        biz_type,
        biz_id,
        biz_purpose,
        COALESCE(owner_user_id, 0)
    )
    WHERE delete_flag = FALSE AND biz_type IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_im_conversation_biz_lookup
    ON im_conversation (biz_type, biz_id, biz_purpose)
    WHERE delete_flag = FALSE AND biz_type IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_im_conversation_biz_key
    ON im_conversation (tenant_id, biz_key)
    WHERE delete_flag = FALSE AND biz_key IS NOT NULL;

ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS run_mode        VARCHAR(16);
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS request_source  VARCHAR(32);
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS source_ref      VARCHAR(128);
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS entity_type     VARCHAR(32);
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS entity_id       BIGINT;
ALTER TABLE agent_work_item ADD COLUMN IF NOT EXISTS scene_id        BIGINT;

-- Preserve legacy chat behavior first. Application runs set READ explicitly.
UPDATE agent_work_item SET run_mode = 'WRITE' WHERE run_mode IS NULL;
ALTER TABLE agent_work_item ALTER COLUMN run_mode SET DEFAULT 'WRITE';
ALTER TABLE agent_work_item ALTER COLUMN run_mode SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_work_item_idem
    ON agent_work_item (idempotency_key)
    WHERE delete_flag = FALSE AND idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_work_item_entity
    ON agent_work_item (entity_type, entity_id, status) WHERE delete_flag = FALSE;
