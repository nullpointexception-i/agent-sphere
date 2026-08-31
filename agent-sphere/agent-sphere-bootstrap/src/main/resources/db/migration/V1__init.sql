-- =============================================================================
-- AgentSphere 一次性初始化脚本（合并历史 Flyway V1..V52）
-- 前提：全新空 POSTGRES（public schema 为空）
-- 内容：33 张表（终态建表归一化）、函数/触发器、索引、种子数据（用户/角色/权限/
--      系统配置/Bole completions），以及全部 SERIAL 序列对齐（setval）。
-- -----------------------------------------------------------------------------
-- 约定（与逐版本演变最终态一致，避免历史陷阱）：
--   * agent_run.loop_capped                     = BOOLEAN NOT NULL DEFAULT FALSE（原 SMALLINT→BOOLEAN）
--   * agent_identity_provider.delete_flag       = SMALLINT（原 BOOLEAN→SMALLINT）
--   * agent_tool_call_record.display_name_cn/en = 两列（原 display_name 改名）
--   * agent_pending_clarification               = 无 expires_at（历史已删）
--   * Bole completions id 1..7 为外部契约，严禁改号；profile_generate 固定 id=8
--   * 角色最终态：仅 USER + DEMO（历史 ADMIN 已删除）
--   * 演示用户 demo001 / demo001（SHA-256），与 README 一致
-- =============================================================================

-- 通用 updated_at 触发函数 --------------------------------------------------
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

SET search_path = public;

-- =============================================================================
-- 1) 会话 / run 体系
-- =============================================================================
CREATE TABLE agent_session (
    id                   BIGSERIAL PRIMARY KEY,
    title                VARCHAR(200) NOT NULL DEFAULT '',
    agent_instance_id    BIGINT NOT NULL,
    context_data         JSONB,
    cached_in_redis      BOOLEAN NOT NULL DEFAULT FALSE,
    summary              TEXT,
    summary_updated_at   TIMESTAMP,
    summary_model_route_id BIGINT,
    title_auto_generated BOOLEAN NOT NULL DEFAULT true,
    status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_session_tenant ON agent_session (tenant_id);
CREATE INDEX idx_session_instance ON agent_session (agent_instance_id);

CREATE TABLE agent_run (
    id                   BIGSERIAL PRIMARY KEY,
    session_id           BIGINT NOT NULL REFERENCES agent_session(id),
    type                 VARCHAR(10) NOT NULL DEFAULT '',
    user_message         TEXT NOT NULL DEFAULT '',
    assistant_reply      TEXT,
    intent_classification TEXT,
    task_id              BIGINT,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reasoning            TEXT,
    loop_capped          BOOLEAN NOT NULL DEFAULT FALSE,
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_run_tenant ON agent_run (tenant_id);
CREATE INDEX idx_run_session ON agent_run (session_id);
CREATE INDEX idx_run_type ON agent_run (type);

CREATE TABLE agent_instance (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(200) NOT NULL DEFAULT '',
    description       TEXT,
    system_prompt     TEXT,
    image             TEXT,
    model_route_id    BIGINT,
    custom_instructions JSONB,
    status            VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    business_type     VARCHAR(64),
    delete_flag       SMALLINT NOT NULL DEFAULT 0,
    tenant_id         BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_instance_tenant ON agent_instance (tenant_id);
CREATE INDEX idx_instance_name ON agent_instance (name);
CREATE INDEX idx_instance_created ON agent_instance (created_at);

CREATE TABLE agent_instance_capability (
    id              BIGSERIAL PRIMARY KEY,
    instance_id     BIGINT NOT NULL REFERENCES agent_instance(id),
    capability_type VARCHAR(20) NOT NULL DEFAULT '',
    capability_id   BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag     SMALLINT NOT NULL DEFAULT 0,
    tenant_id       BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_instcap_bind UNIQUE (instance_id, capability_type, capability_id)
);
CREATE INDEX idx_instcap_tenant ON agent_instance_capability (tenant_id);
CREATE INDEX idx_instcap_instance ON agent_instance_capability (instance_id);

CREATE TABLE agent_memory (
    id            BIGSERIAL PRIMARY KEY,
    type          VARCHAR(20) NOT NULL DEFAULT '',
    session_id    BIGINT,
    run_id        BIGINT,
    task_id       BIGINT,
    summary       TEXT,
    content       JSONB,
    status        VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag   SMALLINT NOT NULL DEFAULT 0,
    tenant_id     BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mem_tenant ON agent_memory (tenant_id);
CREATE INDEX idx_mem_type ON agent_memory (type);
CREATE INDEX idx_mem_session ON agent_memory (session_id);

CREATE TABLE agent_session_todo (
    id            BIGSERIAL PRIMARY KEY,
    session_id    BIGINT NOT NULL,
    run_id        BIGINT,
    content       VARCHAR(500) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending',
    priority      VARCHAR(10) NOT NULL DEFAULT 'medium',
    sort_order    INT NOT NULL DEFAULT 0,
    delete_flag   SMALLINT NOT NULL DEFAULT 0,
    tenant_id     BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_todo_session ON agent_session_todo (session_id);
CREATE INDEX idx_todo_session_run ON agent_session_todo (session_id, run_id);

CREATE TABLE agent_pending_clarification (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT NOT NULL,
    run_id            BIGINT NOT NULL,
    title             VARCHAR(255) NOT NULL,
    type              VARCHAR(32) NOT NULL,
    options           TEXT,
    user_response     TEXT,
    message_id        BIGINT,
    clarification_id  VARCHAR(16),
    created_by        VARCHAR(64) NOT NULL DEFAULT '',
    updated_by        VARCHAR(64) NOT NULL DEFAULT '',
    updated_at        TIMESTAMP,
    delete_flag       SMALLINT NOT NULL DEFAULT 0,
    tenant_id         BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_clarification_session ON agent_pending_clarification (session_id);
CREATE INDEX idx_clarification_run ON agent_pending_clarification (run_id);
CREATE INDEX idx_clarification_message ON agent_pending_clarification (message_id);
CREATE INDEX idx_clarification_id ON agent_pending_clarification (clarification_id);

-- =============================================================================
-- 2) 模型体系
-- =============================================================================
CREATE TABLE agent_model_provider (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL DEFAULT '',
    base_url    VARCHAR(500),
    api_key_id  BIGINT,
    config      JSONB,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag SMALLINT NOT NULL DEFAULT 0,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_provider_tenant ON agent_model_provider (tenant_id);
CREATE INDEX idx_provider_name ON agent_model_provider (name);

CREATE TABLE agent_api_key (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES agent_model_provider(id),
    alias       VARCHAR(128),
    key_value   TEXT NOT NULL DEFAULT '',
    expires_at  TIMESTAMP,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    encrypted   VARCHAR(3) NOT NULL DEFAULT 'NO',
    delete_flag SMALLINT NOT NULL DEFAULT 0,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_apikey_provider ON agent_api_key (provider_id);
CREATE INDEX idx_apikey_tenant ON agent_api_key (tenant_id);

CREATE TABLE agent_model_route (
    id               BIGSERIAL PRIMARY KEY,
    provider_id      BIGINT NOT NULL REFERENCES agent_model_provider(id),
    model_name       VARCHAR(255) NOT NULL DEFAULT '',
    company          VARCHAR(50) DEFAULT 'deepseek',
    weight           INT NOT NULL DEFAULT 100,
    max_input_tokens BIGINT,
    max_output_tokens BIGINT,
    fallback_ids     JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_route_provider ON agent_model_route (provider_id);
CREATE INDEX idx_route_tenant ON agent_model_route (tenant_id);
CREATE INDEX idx_route_model ON agent_model_route (model_name);

-- =============================================================================
-- 3) 能力体系
-- =============================================================================
CREATE TABLE capability_mcp (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL DEFAULT '',
    description      TEXT,
    server_url       VARCHAR(500) NOT NULL DEFAULT '',
    server_type      VARCHAR(20) NOT NULL DEFAULT 'stdio',
    auth_config      JSONB,
    tool_definitions JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_mcp_tenant ON capability_mcp (tenant_id);
CREATE INDEX idx_mcp_name ON capability_mcp (name);

CREATE TABLE capability_skill (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL DEFAULT '',
    description TEXT,
    definition  JSONB,
    status      VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag SMALLINT NOT NULL DEFAULT 0,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_skill_tenant ON capability_skill (tenant_id);
CREATE INDEX idx_skill_name ON capability_skill (name);

CREATE TABLE capability_cli (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL DEFAULT '',
    command_template VARCHAR(500) NOT NULL DEFAULT '',
    param_schema     JSONB,
    working_dir      VARCHAR(500),
    status           VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cli_tenant ON capability_cli (tenant_id);
CREATE INDEX idx_cli_name ON capability_cli (name);

-- =============================================================================
-- 4) 用户 / 文档 / 记录
-- =============================================================================
CREATE TABLE agent_user (
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(100) NOT NULL UNIQUE,
    password       VARCHAR(255) NOT NULL,
    display_name   VARCHAR(200) NOT NULL DEFAULT '',
    email          VARCHAR(200),
    avatar         TEXT,
    english_name   VARCHAR(64),
    super_admin    VARCHAR(10) NOT NULL DEFAULT 'NO',
    token          VARCHAR(500),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMP,
    last_login_ip  VARCHAR(45),
    last_login_ua  VARCHAR(500),
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_tenant ON agent_user (tenant_id);

CREATE TABLE agent_document (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(500) NOT NULL DEFAULT '',
    content      TEXT NOT NULL DEFAULT '',
    content_type VARCHAR(20) NOT NULL DEFAULT 'markdown',
    session_id   BIGINT NOT NULL,
    instance_id  BIGINT NOT NULL,
    run_id       BIGINT,
    share_token  VARCHAR(64),
    delete_flag  SMALLINT NOT NULL DEFAULT 0,
    tenant_id    BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_document_share_token UNIQUE (share_token)
);
CREATE INDEX idx_doc_session ON agent_document (session_id);
CREATE INDEX idx_doc_instance ON agent_document (instance_id);
CREATE INDEX idx_doc_tenant ON agent_document (tenant_id);

CREATE TABLE agent_tool_call_record (
    id                   BIGSERIAL PRIMARY KEY,
    step_id              BIGINT NOT NULL,
    run_id               BIGINT,
    session_id           BIGINT NOT NULL,
    tool_name            VARCHAR(200) NOT NULL,
    display_name_cn      VARCHAR(200),
    display_name_en      VARCHAR(255),
    arguments_json       TEXT,
    artifact             TEXT,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message        TEXT,
    call_id              VARCHAR(255),
    compressed_arguments TEXT,
    compressed_artifact  TEXT,
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_toolcall_step ON agent_tool_call_record (step_id);
CREATE INDEX idx_toolcall_session ON agent_tool_call_record (session_id);
CREATE INDEX idx_toolcall_run ON agent_tool_call_record (run_id);
CREATE INDEX idx_toolcall_call_id ON agent_tool_call_record (call_id);
CREATE INDEX idx_toolcall_run_created ON agent_tool_call_record (run_id, session_id, delete_flag, created_at);

CREATE TABLE agent_compact_record (
    id                   BIGSERIAL PRIMARY KEY,
    session_id           BIGINT NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    summary_before       TEXT,
    summary_after        TEXT,
    token_count          BIGINT,
    compacted_upto_run_id BIGINT DEFAULT 0,
    error_message        TEXT,
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_compact_session ON agent_compact_record (session_id);

CREATE TABLE agent_user_in_loop_record (
    id               BIGSERIAL PRIMARY KEY,
    step_id          BIGINT NOT NULL,
    run_id           BIGINT,
    session_id       BIGINT NOT NULL,
    interaction_type VARCHAR(30) NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'WAITING',
    prompt           TEXT,
    response         TEXT,
    responded_by     VARCHAR(100),
    result           VARCHAR(30),
    comment          TEXT,
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_hitl_step ON agent_user_in_loop_record (step_id);
CREATE INDEX idx_hitl_session ON agent_user_in_loop_record (session_id);

CREATE TABLE agent_llm_interaction_record (
    id               BIGSERIAL PRIMARY KEY,
    run_id           BIGINT,
    session_id       BIGINT,
    interaction_type VARCHAR(50) NOT NULL,
    model_name       VARCHAR(200),
    request_body     TEXT,
    response_body    TEXT,
    http_status      INT,
    duration_ms      INT,
    error_message    TEXT,
    success          BOOLEAN NOT NULL DEFAULT true,
    created_by       VARCHAR(64),
    updated_by       VARCHAR(64),
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP,
    delete_flag      SMALLINT DEFAULT 0,
    tenant_id        BIGINT
);
CREATE INDEX idx_llm_interaction_run ON agent_llm_interaction_record (run_id);
CREATE INDEX idx_llm_interaction_session ON agent_llm_interaction_record (session_id);
CREATE INDEX idx_llm_interaction_created ON agent_llm_interaction_record (created_at);
CREATE INDEX idx_llm_run_created ON agent_llm_interaction_record (run_id, delete_flag, created_at);

-- =============================================================================
-- 5) 系统配置 / 审计 / RBAC
-- =============================================================================
CREATE TABLE agent_system_config (
    id           BIGSERIAL PRIMARY KEY,
    config_group VARCHAR(64) NOT NULL,
    config_key   VARCHAR(128) NOT NULL UNIQUE,
    config_value TEXT,
    is_secret    BOOLEAN NOT NULL DEFAULT FALSE,
    description  VARCHAR(255),
    delete_flag  SMALLINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP DEFAULT NOW(),
    updated_at   TIMESTAMP DEFAULT NOW()
);

CREATE TABLE sys_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    username      VARCHAR(100),
    action        VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64),
    resource_id   VARCHAR(128),
    detail        TEXT,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(500),
    success       BOOLEAN NOT NULL DEFAULT TRUE,
    error_message TEXT,
    created_at    TIMESTAMP DEFAULT NOW(),
    delete_flag   SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_audit_log_user_id ON sys_audit_log (user_id);
CREATE INDEX idx_audit_log_action ON sys_audit_log (action);
CREATE INDEX idx_audit_log_created ON sys_audit_log (created_at);
CREATE INDEX idx_audit_log_resource ON sys_audit_log (resource_type, resource_id);

-- =============================================================================
-- 6) SSO / 身份
-- =============================================================================
CREATE TABLE agent_identity_provider (
    id                      BIGSERIAL PRIMARY KEY,
    code                    VARCHAR(64) NOT NULL UNIQUE,
    type                    VARCHAR(16) NOT NULL DEFAULT 'OIDC',
    name                    VARCHAR(128) NOT NULL,
    issuer                  VARCHAR(512) NOT NULL,
    client_id               VARCHAR(256) NOT NULL,
    client_secret           VARCHAR(1024) NOT NULL,
    authorization_endpoint  VARCHAR(512) NOT NULL,
    token_endpoint          VARCHAR(512) NOT NULL,
    jwks_url                VARCHAR(512) NOT NULL,
    scopes                  VARCHAR(512),
    claim_mappings          JSONB,
    default_role_id         BIGINT,
    enabled                 BOOLEAN NOT NULL DEFAULT TRUE,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark                  VARCHAR(500),
    delete_flag             SMALLINT NOT NULL DEFAULT 0,
    resource_template       JSONB,
    created_by              VARCHAR(100),
    updated_by              VARCHAR(100),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_identity_provider_code ON agent_identity_provider (code);

CREATE TABLE agent_sso_identity (
    id              BIGSERIAL PRIMARY KEY,
    provider_code   VARCHAR(64) NOT NULL,
    subject         VARCHAR(512) NOT NULL,
    agent_user_id   BIGINT NOT NULL,
    display_subject VARCHAR(512),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark          VARCHAR(500),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_sso_identity UNIQUE (provider_code, subject)
);
CREATE INDEX idx_sso_identity_user ON agent_sso_identity (agent_user_id);

-- =============================================================================
-- 7) Completions / Tasks / Artifacts
-- =============================================================================
CREATE TABLE agent_completions (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(200) NOT NULL DEFAULT '',
    description       TEXT,
    model_route_id    BIGINT,
    active_prompt_id  BIGINT,
    input_schema      JSONB,
    output_schema     JSONB,
    config            JSONB,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark            VARCHAR(500),
    business_type     VARCHAR(64),
    delete_flag       SMALLINT NOT NULL DEFAULT 0,
    tenant_id         BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(100),
    updated_by        VARCHAR(100),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE agent_completions_prompt (
    id             BIGSERIAL PRIMARY KEY,
    completions_id BIGINT NOT NULL REFERENCES agent_completions(id),
    version        INT NOT NULL DEFAULT 1,
    prompt_system  TEXT,
    prompt_user    TEXT,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark         VARCHAR(500),
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE agent_completions_call (
    id             BIGSERIAL PRIMARY KEY,
    completions_id BIGINT,
    prompt_id      BIGINT,
    input          JSONB,
    output         TEXT,
    model          VARCHAR(200),
    usage          JSONB,
    status         VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    cost           NUMERIC(14,6),
    caller         VARCHAR(200),
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    remark         VARCHAR(500),
    updated_by     VARCHAR(100),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE agent_task (
    id                   BIGSERIAL PRIMARY KEY,
    goal                 TEXT,
    context_json         JSONB,
    expected_output_json JSONB,
    config               JSONB,
    instance_id          BIGINT,
    session_id           BIGINT,
    run_id               BIGINT,
    status               VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    result_json          JSONB,
    remark               VARCHAR(500),
    callback_url         VARCHAR(500),
    poll_phase           VARCHAR(20),
    polled_at            TIMESTAMP,
    started_at           TIMESTAMP,
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE agent_task_artifact (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES agent_task(id),
    artifact_type VARCHAR(50) NOT NULL DEFAULT 'task_contract',
    content       TEXT,
    schema_ref    TEXT,
    run_id        BIGINT,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark        VARCHAR(500),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_task_artifact_task ON agent_task_artifact (task_id);

-- =============================================================================
-- 8) 通用文件存储 / RBAC 基础表
-- =============================================================================
CREATE TABLE agent_file_store (
    id           BIGSERIAL PRIMARY KEY,
    biz_key      VARCHAR(64) NOT NULL,
    file_key     VARCHAR(128) NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content      BYTEA NOT NULL,
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(500),
    delete_flag  SMALLINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_file_biz UNIQUE (biz_key, file_key)
);

CREATE TABLE sys_permission (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(20) NOT NULL DEFAULT 'BUTTON',
    parent_id   BIGINT REFERENCES sys_permission(id),
    sort        INT DEFAULT 0,
    description VARCHAR(255),
    status      VARCHAR(20) DEFAULT 'ACTIVE',
    remark      VARCHAR(500),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP DEFAULT now(),
    updated_at  TIMESTAMP
);

CREATE TABLE sys_role (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    code        VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    status      VARCHAR(20) DEFAULT 'ACTIVE',
    remark      VARCHAR(500),
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP DEFAULT now(),
    updated_at  TIMESTAMP
);

CREATE TABLE sys_role_permission (
    id            BIGSERIAL PRIMARY KEY,
    role_id       BIGINT NOT NULL REFERENCES sys_role(id),
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id),
    status        VARCHAR(20) DEFAULT 'ACTIVE',
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP DEFAULT now(),
    updated_at    TIMESTAMP,
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

CREATE TABLE sys_user_role (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES agent_user(id),
    role_id     BIGINT NOT NULL REFERENCES sys_role(id),
    status      VARCHAR(20) DEFAULT 'ACTIVE',
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP DEFAULT now(),
    updated_at  TIMESTAMP,
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

-- =============================================================================
-- 9) updated_at 触发器（有 updated_at 且历史带触发器的表）
-- =============================================================================
CREATE TRIGGER trg_session_updated_at BEFORE UPDATE ON agent_session FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_run_updated_at BEFORE UPDATE ON agent_run FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_instance_updated_at BEFORE UPDATE ON agent_instance FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_instcap_updated_at BEFORE UPDATE ON agent_instance_capability FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_memory_updated_at BEFORE UPDATE ON agent_memory FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_provider_updated_at BEFORE UPDATE ON agent_model_provider FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_apikey_updated_at BEFORE UPDATE ON agent_api_key FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_route_updated_at BEFORE UPDATE ON agent_model_route FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_mcp_updated_at BEFORE UPDATE ON capability_mcp FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_skill_updated_at BEFORE UPDATE ON capability_skill FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_cli_updated_at BEFORE UPDATE ON capability_cli FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_user_updated_at BEFORE UPDATE ON agent_user FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
-- =============================================================================
-- 种子：用户 + 系统配置
-- =============================================================================

-- 管理员（任务/client 全局账户）
INSERT INTO agent_user (username, password, display_name, super_admin, status)
VALUES ('admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', 'Administrator', 'YES',
        'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 演示账号（README: demo001 / demo001）
INSERT INTO agent_user (username, password, display_name, super_admin, status)
VALUES ('demo001', '5f00b206f45b5c43b6b8808d1db29e51b92258a652aae29b0972f03b4a254f8c', '演示账户', 'NO',
        'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 系统配置（合并 V9/V30/V46/V49 终态：9 行）
INSERT INTO agent_system_config (config_group, config_key, config_value, is_secret, description) VALUES
('security',   'crypto.aes-key',              '',  true,  'AES-256-GCM encryption key (auto-generated, base64)'),
('chrome',     'chrome.extension-token',      '',  false, 'Chrome extension callback authentication token'),
('web-read',   'web-read.jina-api-key',       '',  true,  'Jina Reader API key for web page reading'),
('rate-limit', 'rate-limit.login-max-attempts', '5', false, 'Maximum login attempts per time window'),
('rate-limit', 'rate-limit.login-window-minutes','1', false, 'Login rate limit time window (minutes)'),
('sso',        'sso.base-url', 'http://as.buukle.top', false, 'SSO 回调地址基础 URL'),
('plugin',     'plugin.download-url', '', false, 'Chrome 插件安装包下载地址（应用内上传托管，或填写外链；留空不展示入口）'),
('user',       'user.resource-template', '', false, '自助注册用户初始化资源模板（JSON，留空使用默认模板）'),
('plugin',     'plugin.store-url', 'https://chromewebstore.google.com/detail/agentsphere/cpjaeggemhjndbnepjifgckodnlfhfkg?hl=zh-CN&utm_source=ext_sidebar', false, 'Chrome 插件应用市场（Chrome Web Store）下载地址；留空不展示应用市场选项');

-- Bole 身份源（SSO/OIDC）种子
INSERT INTO agent_identity_provider
    (code, type, name, issuer, client_id, client_secret,
     authorization_endpoint, token_endpoint, jwks_url,
     scopes, enabled, status, created_by)
VALUES
    ('bole', 'OIDC', '伯乐 Bole', 'http://bole.buukle.top', 'agent-sphere', '',
     'http://bole.buukle.top/oauth2/authorize', 'http://bole.buukle.top/oauth2/token',
     'http://bole.buukle.top/oauth2/jwks',
     'openid profile email', TRUE, 'ACTIVE', 'admin')
ON CONFLICT (code) DO NOTHING;

-- ============ RBAC 种子（合并 V12..V36 终态；ADMIN 角色历史已删除） ============
-- Insert roles
INSERT INTO sys_role (name, code, description) VALUES ('普通用户', 'USER', '普通用户，拥有基本操作权限');
INSERT INTO sys_role (name, code, description) VALUES ('演示账户', 'DEMO', '演示账户，仅拥有只读和基本对话权限');

-- Insert permissions
INSERT INTO sys_permission (name, code, type, sort) VALUES ('用户管理', 'user', 'MENU', 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('修改密码', 'user:password:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'user'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('修改资料', 'user:profile:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'user'), 2);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('文档管理', 'document', 'MENU', 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档创建', 'document:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档查看', 'document:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档编辑', 'document:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档删除', 'document:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('文档分享', 'document:share', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'document'), 5);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('模型供应商', 'model', 'MENU', 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商创建', 'model:provider:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商查看', 'model:provider:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商编辑', 'model:provider:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('供应商删除', 'model:provider:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥创建', 'model:apikey:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥查看', 'model:apikey:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥编辑', 'model:apikey:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 7);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥删除', 'model:apikey:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 8);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('API密钥设为当前', 'model:apikey:set-active', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 9);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由创建', 'model:route:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 10);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由查看', 'model:route:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 11);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由编辑', 'model:route:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 12);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('路由删除', 'model:route:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'model'), 13);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('能力管理', 'capability', 'MENU', 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP创建', 'capability:mcp:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP查看', 'capability:mcp:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP编辑', 'capability:mcp:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('MCP删除', 'capability:mcp:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill创建', 'capability:skill:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill查看', 'capability:skill:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill编辑', 'capability:skill:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 7);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('Skill删除', 'capability:skill:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 8);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI创建', 'capability:cli:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 9);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI查看', 'capability:cli:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 10);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI编辑', 'capability:cli:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 11);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('CLI删除', 'capability:cli:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 12);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('内置能力查看', 'capability:builtin:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'capability'), 13);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('实例管理', 'instance', 'MENU', 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例创建', 'instance:create', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例查看', 'instance:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例编辑', 'instance:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例删除', 'instance:delete', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例绑定能力', 'instance:capability:bind', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 5);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('实例解绑能力', 'instance:capability:unbind', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'instance'), 6);

INSERT INTO sys_permission (name, code, type, sort) VALUES ('系统设置', 'admin', 'MENU', 6);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('设置查看', 'admin:settings:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 1);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('设置修改', 'admin:settings:update', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 2);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('AES密钥重新生成', 'admin:settings:regenerate-aes', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 3);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('角色查看', 'admin:role:read', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 4);
INSERT INTO sys_permission (name, code, type, parent_id, sort) VALUES ('角色分配', 'admin:role:assign', 'BUTTON', (SELECT id FROM sys_permission WHERE code = 'admin'), 5);


-- USER role gets all except admin:settings:* and admin:role:*
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'USER'), id FROM sys_permission
WHERE code NOT LIKE 'admin:%';

-- DEMO role gets read-only + basic chat permissions
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT (SELECT id FROM sys_role WHERE code = 'DEMO'), id FROM sys_permission
WHERE code IN (
    'document:read',
    'model:provider:read',
    'model:apikey:read',
    'model:route:read',
    'capability:mcp:read',
    'capability:skill:read',
    'capability:cli:read',
    'capability:builtin:read',
    'instance:read'
);

-- Grant admin read permissions to USER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'USER'
  AND p.code IN ('admin:settings:read', 'admin:role:read')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Add sub-menu permissions under admin
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户管理', 'admin:user', 'MENU', id, 1 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色管理', 'admin:role', 'MENU', id, 2 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限管理', 'admin:permission', 'MENU', id, 3 FROM sys_permission WHERE code = 'admin';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '系统配置', 'admin:settings', 'MENU', id, 4 FROM sys_permission WHERE code = 'admin';

-- Move existing BUTTONs under correct sub-menu
UPDATE sys_permission SET parent_id = (SELECT id FROM sys_permission WHERE code = 'admin:role')
WHERE code IN ('admin:role:read', 'admin:role:assign');
UPDATE sys_permission SET parent_id = (SELECT id FROM sys_permission WHERE code = 'admin:settings')
WHERE code IN ('admin:settings:read', 'admin:settings:update', 'admin:settings:regenerate-aes');

-- Add missing BUTTON permissions under admin:user and admin:permission
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户查看', 'admin:user:read', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:user';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '用户分配角色', 'admin:user:assign-role', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:user';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限查看', 'admin:permission:read', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限创建', 'admin:permission:create', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限编辑', 'admin:permission:update', 'BUTTON', id, 3 FROM sys_permission WHERE code = 'admin:permission';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '权限删除', 'admin:permission:delete', 'BUTTON', id, 4 FROM sys_permission WHERE code = 'admin:permission';

-- Grant new permissions to USER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER'
  AND p.code IN ('admin:user:read', 'admin:permission:read', 'admin:permission:create', 'admin:permission:update', 'admin:permission:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

UPDATE sys_permission SET name = '个人资料' WHERE code = 'user';

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色创建', 'admin:role:create', 'BUTTON', id, 1 FROM sys_permission WHERE code = 'admin:role';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色编辑', 'admin:role:update', 'BUTTON', id, 2 FROM sys_permission WHERE code = 'admin:role';
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '角色删除', 'admin:role:delete', 'BUTTON', id, 3 FROM sys_permission WHERE code = 'admin:role';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code IN ('admin:role:create', 'admin:role:update', 'admin:role:delete')
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '实例绑定模型', 'instance:bind-model', 'BUTTON', id, 7 FROM sys_permission WHERE code = 'instance';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code = 'instance:bind-model'
AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '审计日志', 'admin:audit-log', 'MENU', id, 5, '操作审计日志'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:audit-log');

-- BUTTON
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '审计日志查看', 'admin:audit-log:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:audit-log'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:audit-log:read');

-- Grant to USER role
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'USER' AND p.code IN ('admin:audit-log', 'admin:audit-log:read')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- 身份源管理（SSO/OIDC）权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '身份源管理', 'admin:identity-provider', 'MENU', id, 7, 'SSO/OIDC 身份源管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源查看', 'admin:identity-provider:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源创建', 'admin:identity-provider:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源修改', 'admin:identity-provider:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '身份源删除', 'admin:identity-provider:delete', 'BUTTON', id, 4
FROM sys_permission WHERE code = 'admin:identity-provider'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:identity-provider:delete');

-- 授权给 ADMIN（V12 的全量授权是一次性快照，新增权限需显式补齐）与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:identity-provider', 'admin:identity-provider:read',
                 'admin:identity-provider:create', 'admin:identity-provider:update',
                 'admin:identity-provider:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- Completions 能力管理权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT 'Completions 管理', 'admin:completions', 'MENU', id, 8, '单次 LLM 能力（completions）管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 查看', 'admin:completions:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 创建', 'admin:completions:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 修改', 'admin:completions:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:update');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT 'Completions 删除', 'admin:completions:delete', 'BUTTON', id, 4
FROM sys_permission WHERE code = 'admin:completions'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:completions:delete');

-- 授权给 ADMIN 与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:completions', 'admin:completions:read',
                 'admin:completions:create', 'admin:completions:update',
                 'admin:completions:delete')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);

-- Tasks 能力管理权限：MENU + 操作 BUTTON，授权给 ADMIN 与 USER

-- MENU
INSERT INTO sys_permission (name, code, type, parent_id, sort, description)
SELECT '任务管理', 'admin:tasks', 'MENU', id, 9, '目标驱动多轮任务（tasks）管理'
FROM sys_permission WHERE code = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks');

-- BUTTONs
INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务查看', 'admin:tasks:read', 'BUTTON', id, 1
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:read');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务创建', 'admin:tasks:create', 'BUTTON', id, 2
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:create');

INSERT INTO sys_permission (name, code, type, parent_id, sort)
SELECT '任务更新', 'admin:tasks:update', 'BUTTON', id, 3
FROM sys_permission WHERE code = 'admin:tasks'
  AND NOT EXISTS (SELECT 1 FROM sys_permission WHERE code = 'admin:tasks:update');

-- 授权给 ADMIN 与 USER
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code IN ('ADMIN', 'USER')
  AND p.code IN ('admin:tasks', 'admin:tasks:read',
                 'admin:tasks:create', 'admin:tasks:update')
  AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);


-- ============ Bole completions 种子（合并 V34/V50/V51/V52） ============
-- V34: Bole 集成 completions seed（幂等，固定 id 1-7）
-- 契约：Bole 侧 system_settings.agent_sphere_completions 维护 code→id 映射，id 必须稳定
-- model_route_id 先置 NULL：模型路由由管理员手动配置后，再绑定（见集成文档运维步骤）
-- input_schema/output_schema 仅存储，as 不校验

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(1, '简历解析', '从简历信息提取结构化字段（Bole: resume_parse）', NULL, NULL,
 '{"type":"object","required":["resumeText"],"properties":{"resumeText":{"type":"string","description":"简历原始文本"},"candidateId":{"type":"integer","description":"Bole 候选人 id"}}}'::jsonb,
 '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(2, '5 维匹配', '按技能/职位/行业/目标公司/年限 5 维评分（Bole: five_dim_match，当前无生产调用）', NULL, NULL,
 '{"type":"object","required":["candidateProfile","jobRequirement"],"properties":{"candidateProfile":{"type":"object","description":"候选人画像"},"jobRequirement":{"type":"object","description":"职位要求"}}}'::jsonb,
 '{"type":"object","required":["scores","overall"],"properties":{"scores":{"type":"object","properties":{"skill":{"type":"integer","minimum":0,"maximum":100},"position":{"type":"integer","minimum":0,"maximum":100},"industry":{"type":"integer","minimum":0,"maximum":100},"targetCompany":{"type":"integer","minimum":0,"maximum":100},"years":{"type":"integer","minimum":0,"maximum":100}}},"overall":{"type":"integer","minimum":0,"maximum":100},"comment":{"type":"string"}}}'::jsonb,
 '{"temperature":0.2}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(3, 'AI 触达话术', '生成面向候选人的暖场触达话术（Bole: outreach）', NULL, NULL,
 '{"type":"object","required":["candidate"],"properties":{"candidate":{"type":"object","description":"候选人信息"},"job":{"type":"object","description":"职位信息"},"channel":{"type":"string","description":"触达渠道，如 wechat/email"}}}'::jsonb,
 '{"type":"object","required":["message"],"properties":{"message":{"type":"string","description":"触达话术文案"}}}'::jsonb,
 '{"temperature":0.7}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(4, '自然语言搜索', '将自然语言寻访需求解析为结构化筛选条件（Bole: nl_search）', NULL, NULL,
 '{"type":"object","required":["query"],"properties":{"query":{"type":"string","description":"自然语言寻访需求"}}}'::jsonb,
 '{"type":"object","properties":{"industry":{"type":"string"},"skills":{"type":"array","items":{"type":"string"}},"city":{"type":"string"},"education":{"type":"string"},"minYears":{"type":"integer"},"maxYears":{"type":"integer"},"minSalary":{"type":"integer"},"summary":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(5, '组织收集', '根据目标公司整理组织架构与联系信息（Bole: org_collect，当前纯 mock）', NULL, NULL,
 '{"type":"object","required":["company"],"properties":{"company":{"type":"string","description":"目标公司名称"},"depth":{"type":"integer","description":"收集层级深度"}}}'::jsonb,
 '{"type":"object","properties":{"departments":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"manager":{"type":"string"},"contact":{"type":"string"}}}},"note":{"type":"string"}}}'::jsonb,
 '{"temperature":0.1}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(6, '评估报告推荐理由', '面向客户生成推荐理由（Bole: recommend_reason）', NULL, NULL,
 '{"type":"object","required":["candidate","position"],"properties":{"candidate":{"type":"object","description":"候选人画像"},"position":{"type":"object","description":"职位要求"},"customer":{"type":"object","description":"客户信息"}}}'::jsonb,
 '{"type":"object","required":["reason"],"properties":{"reason":{"type":"string"},"highlights":{"type":"array","items":{"type":"string"}},"risks":{"type":"array","items":{"type":"string"}}}}'::jsonb,
 '{"temperature":0.3}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO agent_completions (id, name, description, model_route_id, active_prompt_id, input_schema, output_schema, config, status, tenant_id, created_by)
VALUES
(7, 'AI 面试题', '按岗位画像与简历差距生成面试题（Bole: interview_questions）', NULL, NULL,
 '{"type":"object","required":["jobProfile","resume"],"properties":{"jobProfile":{"type":"object","description":"岗位画像"},"resume":{"type":"object","description":"候选人简历"}}}'::jsonb,
 '{"type":"object","required":["questions"],"properties":{"questions":{"type":"array","items":{"type":"object","properties":{"dimension":{"type":"string"},"type":{"type":"string","enum":["behavioral","technical","situational"]},"content":{"type":"string"}}}}}}'::jsonb,
 '{"temperature":0.6}'::jsonb, 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

-- prompts（每个 completions 一个 version=1，显式 id 与 completionsId 对齐）
INSERT INTO agent_completions_prompt (id, completions_id, version, prompt_system, prompt_user, status, tenant_id, created_by)
VALUES
(1, 1, 1, '你是资深招聘顾问，从简历信息提取结构化字段。只输出 JSON，不要额外说明。', '{{input}}', 'ACTIVE', 0, 'system'),
(2, 2, 1, '你是资深猎头，请按 技能/职位/行业/目标公司/年限 5 维对候选人匹配度评分(0-100)。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(3, 3, 1, '你是招聘顾问，为候选人生成自然真诚的暖场触达话术。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(4, 4, 1, '你将自然语言寻访需求解析为结构化筛选条件(industry/skills/city/education/minYears/maxYears/minSalary/summary)。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(5, 5, 1, '你根据目标公司整理组织架构与联系信息。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(6, 6, 1, '你面向客户生成候选人推荐理由，突出亮点并提示风险。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system'),
(7, 7, 1, '你根据岗位画像与候选人简历差距生成面试题(questions:[{dimension,type,content}])。只输出 JSON。', '{{input}}', 'ACTIVE', 0, 'system')
ON CONFLICT (id) DO NOTHING;

-- 回填 active_prompt_id（幂等：仅当为空时）
UPDATE agent_completions SET active_prompt_id = id WHERE id BETWEEN 1 AND 7 AND active_prompt_id IS NULL;

-- -----------------------------------------------------------------------------
-- Bole completions 桥接：补 business_type + 序列对齐（V34 显式 id 不回绕序列，
-- 需在 V51 序列插入前 setval；business_type 原由运行时填充，此处补齐保证
-- V50/V51/V52 语义在新库命中）。
-- -----------------------------------------------------------------------------
UPDATE agent_completions SET business_type = 'resume_parse' WHERE id = 1;
UPDATE agent_completions SET business_type = 'five_dim_match' WHERE id = 2;
UPDATE agent_completions SET business_type = 'outreach' WHERE id = 3;
UPDATE agent_completions SET business_type = 'nl_search' WHERE id = 4;
UPDATE agent_completions SET business_type = 'org_collect' WHERE id = 5;
UPDATE agent_completions SET business_type = 'recommend_reason' WHERE id = 6;
UPDATE agent_completions SET business_type = 'interview_questions' WHERE id = 7;
SELECT setval('agent_completions_id_seq', 7, true);
SELECT setval('agent_completions_prompt_id_seq', 7, true);
-- V50: 历史补全 resume_parse 的 output_schema，新增 projectExperience（Bole 项目经历导入）。
-- 运行时按 business_type + created_by(调用用户) 匹配，存在系统行与每用户私有副本，需全量更新。
-- 整体替换（幂等，可重复执行）。
UPDATE agent_completions
SET output_schema = '{"type":"object","required":["name","summary"],"properties":{"name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},"gender":{"type":"string"},"age":{"type":"integer"},"education":{"type":"array","items":{"type":"object","properties":{"school":{"type":"string"},"degree":{"type":"string"},"major":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"}}}},"workExperience":{"type":"array","items":{"type":"object","properties":{"company":{"type":"string"},"title":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"description":{"type":"string"}}}},"projectExperience":{"type":"array","items":{"type":"object","properties":{"projectName":{"type":"string"},"role":{"type":"string"},"startDate":{"type":"string"},"endDate":{"type":"string"},"responsibilities":{"type":"string"}}}},"skills":{"type":"array","items":{"type":"string"}},"summary":{"type":"string"}}}'::jsonb,
    updated_at = NOW()
WHERE business_type = 'resume_parse'
  AND delete_flag = 0
  AND status = 'ACTIVE';

-- V51: 为历史库补全 Bole 岗位画像生成 completions 资源（profile_generate）
-- 运行时按 business_type + created_by(=AS 调用者用户名) + ACTIVE 匹配；
-- 平台 'system' 行仅作组织/模板展示，真实调用走各用户私有副本（created_by = 用户用户名）。
-- 本迁移幂等：已存在同名(business_type+created_by)非删除记录则跳过。
-- id 由 BIGSERIAL 自增，迁移不指定显式 id。
DO $$
DECLARE
    r RECORD;
    cid BIGINT;
    pid BIGINT;
BEGIN
    -- 目标 owner = 平台 system + 已开通 Bole 资源集（以 resume_parse 私有副本作为标记）的用户
    FOR r IN
        SELECT DISTINCT created_by AS owner
        FROM agent_completions
        WHERE business_type = 'resume_parse'
          AND delete_flag = 0
          AND status = 'ACTIVE'
          AND created_by IS NOT NULL
        UNION
        SELECT 'system'
    LOOP
        IF EXISTS (SELECT 1 FROM agent_completions
                   WHERE business_type = 'profile_generate'
                     AND created_by = r.owner
                     AND delete_flag = 0) THEN
            CONTINUE;
        END IF;

        INSERT INTO agent_completions
            (name, description, model_route_id, active_prompt_id, input_schema, output_schema, config,
             business_type, status, delete_flag, tenant_id, created_by)
        VALUES
            ('岗位画像生成', '按 JD 与基础信息生成岗位 9 区块画像（Bole: profile_generate）', NULL, NULL,
             '{"type":"object","required":["title","jd"],"properties":{"title":{"type":"string","description":"职位名称"},"jd":{"type":"string","description":"职位描述"},"department":{"type":"string","description":"所属部门"},"salaryMin":{"type":"number","description":"最低年薪(元)"},"salaryMax":{"type":"number","description":"最高年薪(元)"},"workCity":{"type":"string","description":"工作城市"}}}'::jsonb,
             '{"type":"object","required":["hard","target","experience","ability","character","keywords","search","preference","aiSummary"],"properties":{"positionId":{"type":"integer"},"hard":{"type":"object","properties":{"degree":{"type":"string","description":"学历"},"years":{"type":"string","description":"经验年限"},"industry":{"type":"string","description":"行业"},"skills":{"type":"array","items":{"type":"string"}},"background":{"type":"string","description":"背景"}}},"target":{"type":"object","properties":{"tier":{"type":"string"},"industry":{"type":"string"},"level":{"type":"string"},"salary":{"type":"string"},"companies":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"},"tier":{"type":"string"},"fit":{"type":"string"},"reason":{"type":"string"}}}}}},"experience":{"type":"object","properties":{"years":{"type":"string"},"manageYears":{"type":"string"},"industryExp":{"type":"string"},"stability":{"type":"string"}}},"ability":{"type":"object","properties":{"coreSkills":{"type":"array","items":{"type":"string"}},"plusSkills":{"type":"array","items":{"type":"string"}}}},"character":{"type":"object","properties":{"traits":{"type":"array","items":{"type":"string"}}}},"keywords":{"type":"array","items":{"type":"object","properties":{"keyword":{"type":"string"},"category":{"type":"string"},"priority":{"type":"string"},"remark":{"type":"string"}}}},"search":{"type":"object","properties":{"desc":{"type":"string"},"linkedin":{"type":"string"},"maimai":{"type":"string"},"boss":{"type":"string"},"liepin":{"type":"string"},"skillWords":{"type":"array","items":{"type":"string"}},"companyWords":{"type":"array","items":{"type":"string"}},"excludeWords":{"type":"array","items":{"type":"string"}},"platforms":{"type":"array","items":{"type":"string"}}}},"preference":{"type":"object","properties":{"interviewerFocus":{"type":"string"},"highFreq":{"type":"array","items":{"type":"string"}},"keywords":{"type":"array","items":{"type":"string"}},"salarySensitivity":{"type":"string"},"interviewStyle":{"type":"string"},"eliminate":{"type":"array","items":{"type":"string"}},"sinkFeedback":{"type":"string"}}},"aiSummary":{"type":"string","description":"画像精髓总结"}}}'::jsonb,
             '{"temperature":0.3,"thinking":false}'::jsonb,
             'profile_generate', 'ACTIVE', 0, 0, r.owner)
        RETURNING id INTO cid;

        INSERT INTO agent_completions_prompt
            (completions_id, version, prompt_system, prompt_user, status, delete_flag, tenant_id, created_by)
        VALUES
            (cid, 1,
             '你是资深招聘顾问。根据职位名称、职位描述与基础信息，生成标准化的岗位画像，包含 9 个区块：hard(硬性要求)、target(目标人选画像)、experience(经验背景)、ability(能力)、character(特质)、keywords(关键词)、search(寻访线索与渠道)、preference(考察偏好)、aiSummary(画像精髓总结)。请严格按 JSON Schema 输出，只输出 JSON，不要额外说明。',
             '{{input}}', 'ACTIVE', 0, 0, r.owner)
        RETURNING id INTO pid;

        UPDATE agent_completions SET active_prompt_id = pid, updated_at = NOW() WHERE id = cid;
    END LOOP;
END $$;

-- V52: 为历史库 profile_generate 补全全量职位上下文入参（input_schema）并改写 prompt_system
-- 仅 UPDATE 已存在的 profile_generate 行（平台 system 行 + 各用户私有副本，delete_flag = 0）。
-- 不改动已执行的 V51；幂等：重复执行结果一致（按 business_type 无条件覆盖为新值）。
DO $$
DECLARE
    r RECORD;
    new_input jsonb  := '{"type":"object","required":["title","jd"],"properties":{"clientName":{"type":"string","description":"客户名称"},"title":{"type":"string","description":"职位名称"},"jd":{"type":"string","description":"职位描述"},"department":{"type":"string","description":"所属部门"},"salaryMin":{"type":"number","description":"最低年薪(元)"},"salaryMax":{"type":"number","description":"最高年薪(元)"},"workCity":{"type":"string","description":"工作城市"},"jobCategory":{"type":"string","description":"职能/行业"},"priority":{"type":"string","description":"优先级"},"recruitType":{"type":"string","description":"招聘类型"},"recruitCount":{"type":"integer","description":"招聘人数"},"candidateNationality":{"type":"string","description":"目标候选人国籍"},"country":{"type":"string","description":"国家/地区"},"salaryType":{"type":"string","description":"薪资类型"},"salaryUnit":{"type":"string","description":"薪资单位"},"feeAmount":{"type":"string","description":"服务费"},"paymentMethod":{"type":"string","description":"付款方式"},"status":{"type":"string","description":"职位状态"},"warrantyPeriod":{"type":"integer","description":"保证期(天)"},"interviewRounds":{"type":"integer","description":"面试轮次"},"budgetVsMarket":{"type":"string","description":"预算对比市场"},"expectedJoin":{"type":"string","description":"期望到岗时间"},"expectedFill":{"type":"string","description":"期望完成时间"},"remarks":{"type":"string","description":"备注"}}}'::jsonb;
    new_system text := '你是资深招聘顾问。根据职位基础信息 JSON (input) 生成标准化的岗位画像，包含 9 个区块：hard(硬性要求)、target(目标人选画像)、experience(经验背景)、ability(能力)、character(特质)、keywords(关键词)、search(寻访线索与渠道)、preference(考察偏好)、aiSummary(画像精髓总结)。input 可能包含：clientName(客户名称)、title(职位名称)、jd(职位描述)、department(部门)、jobCategory(职能/行业)、workCity(工作城市)、country(国家/地区)、salaryMin~salaryMax(年薪范围，单位元)、salaryType/salaryUnit(薪资类型/单位)、priority(优先级)、recruitType(招聘类型)、recruitCount(招聘人数)、candidateNationality(目标候选人国籍)、feeAmount(服务费)、paymentMethod(付款方式)、warrantyPeriod(保证期/天)、interviewRounds(面试轮次)、status(职位状态)、budgetVsMarket(预算对比市场)、expectedJoin(期望到岗)、expectedFill(期望完成)、remarks(备注)。请严格按 JSON Schema 输出，只输出 JSON，不要额外说明。';
BEGIN
    FOR r IN
        SELECT id, active_prompt_id
        FROM agent_completions
        WHERE business_type = 'profile_generate'
          AND delete_flag = 0
    LOOP
        UPDATE agent_completions
           SET input_schema = new_input,
               updated_at   = NOW()
         WHERE id = r.id;

        IF r.active_prompt_id IS NOT NULL THEN
            UPDATE agent_completions_prompt
               SET prompt_system = new_system,
                   updated_at    = NOW()
             WHERE completions_id = r.id
               AND id             = r.active_prompt_id
               AND delete_flag    = 0;
        END IF;
    END LOOP;
END $$;

-- =============================================================================
-- 10) SERIAL 序列对齐（防止后续显式 id/序列插入冲突）
--     仅覆盖有种子数据的表；以最终行数为准（is_called=true → 下一个 nextval = N+1）。
-- =============================================================================
SELECT setval('agent_user_id_seq', (SELECT COUNT(*)::bigint FROM agent_user), true);
SELECT setval('sys_role_id_seq', (SELECT COUNT(*)::bigint FROM sys_role), true);
SELECT setval('sys_permission_id_seq', (SELECT COUNT(*)::bigint FROM sys_permission), true);
SELECT setval('sys_role_permission_id_seq', (SELECT COUNT(*)::bigint FROM sys_role_permission), true);
SELECT setval('agent_system_config_id_seq', (SELECT COUNT(*)::bigint FROM agent_system_config), true);
-- Bole completions/prompts：id 1..7 显式 + profile_generate=8（外部契约，勿改号）
SELECT setval('agent_completions_id_seq', (SELECT COUNT(*)::bigint FROM agent_completions), true);
SELECT setval('agent_completions_prompt_id_seq', (SELECT COUNT(*)::bigint FROM agent_completions_prompt), true);
-- Bole 身份源（1 行）
SELECT setval('agent_identity_provider_id_seq', (SELECT COUNT(*)::bigint FROM agent_identity_provider), true);