-- SSO 身份源配置与身份绑定表
-- agent_identity_provider: OIDC Provider 配置（多身份源，仅加行即可扩展）
-- agent_sso_identity: (provider_code, subject) 唯一，映射独立本地用户，不做跨身份源归并

CREATE TABLE IF NOT EXISTS agent_identity_provider (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(64)  NOT NULL UNIQUE,
    type                  VARCHAR(16)  NOT NULL DEFAULT 'OIDC',
    name                  VARCHAR(128) NOT NULL,
    issuer                VARCHAR(512) NOT NULL,
    client_id             VARCHAR(256) NOT NULL,
    client_secret         VARCHAR(1024) NOT NULL,
    authorization_endpoint VARCHAR(512) NOT NULL,
    token_endpoint        VARCHAR(512) NOT NULL,
    jwks_url              VARCHAR(512) NOT NULL,
    scopes                VARCHAR(512) NULL,
    claim_mappings        JSONB        NULL,
    default_role_id       BIGINT       NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark                VARCHAR(500) NULL,
    delete_flag           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by            VARCHAR(100) NULL,
    updated_by            VARCHAR(100) NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_identity_provider_code ON agent_identity_provider(code);

CREATE TABLE IF NOT EXISTS agent_sso_identity (
    id             BIGSERIAL PRIMARY KEY,
    provider_code  VARCHAR(64)  NOT NULL,
    subject        VARCHAR(512) NOT NULL,
    agent_user_id  BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark         VARCHAR(500) NULL,
    created_by     VARCHAR(100) NULL,
    updated_by     VARCHAR(100) NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (provider_code, subject)
);

CREATE INDEX IF NOT EXISTS idx_sso_identity_user ON agent_sso_identity(agent_user_id);
