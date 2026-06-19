CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- agent_session
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_session (
    id                    BIGSERIAL PRIMARY KEY,
    title                 VARCHAR(200) DEFAULT '',
    agent_instance_id     BIGINT NOT NULL,
    context_data          JSONB,
    cached_in_redis       BOOLEAN NOT NULL DEFAULT FALSE,
    summary               TEXT,
    summary_updated_at    TIMESTAMP,
    summary_model_route_id BIGINT,
    title_auto_generated  BOOLEAN NOT NULL DEFAULT true,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag           SMALLINT NOT NULL DEFAULT 0,
    tenant_id             BIGINT NOT NULL DEFAULT 0,
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_session_tenant ON agent_session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_session_instance ON agent_session(agent_instance_id);
CREATE TRIGGER trg_session_updated_at BEFORE UPDATE ON agent_session
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_run
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_run (
    id                   BIGSERIAL PRIMARY KEY,
    session_id           BIGINT NOT NULL REFERENCES agent_session(id),
    type                 VARCHAR(10) NOT NULL DEFAULT '',
    user_message         TEXT NOT NULL DEFAULT '',
    assistant_reply      TEXT,
    intent_classification TEXT,
    task_id              BIGINT,
    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_run_tenant ON agent_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_run_session ON agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_run_type ON agent_run(type);
CREATE TRIGGER trg_run_updated_at BEFORE UPDATE ON agent_run
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_instance
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_instance (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(200) NOT NULL DEFAULT '',
    description         TEXT,
    system_prompt       TEXT,
    image               TEXT,
    model_route_id      BIGINT,
    custom_instructions JSONB,
    status              VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag         SMALLINT NOT NULL DEFAULT 0,
    tenant_id           BIGINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_instance_tenant ON agent_instance(tenant_id);
CREATE INDEX IF NOT EXISTS idx_instance_name ON agent_instance(name);
CREATE TRIGGER trg_instance_updated_at BEFORE UPDATE ON agent_instance
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_instance_capability
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_instance_capability (
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
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_instcap_tenant ON agent_instance_capability(tenant_id);
CREATE INDEX IF NOT EXISTS idx_instcap_instance ON agent_instance_capability(instance_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_instcap_bind ON agent_instance_capability(instance_id, capability_type, capability_id);
CREATE TRIGGER trg_instcap_updated_at BEFORE UPDATE ON agent_instance_capability
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_memory
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_memory (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(20) NOT NULL DEFAULT '',
    session_id  BIGINT,
    run_id      BIGINT,
    task_id     BIGINT,
    summary     TEXT,
    content     JSONB,
    status      VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
    delete_flag SMALLINT NOT NULL DEFAULT 0,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_mem_tenant ON agent_memory(tenant_id);
CREATE INDEX IF NOT EXISTS idx_mem_type ON agent_memory(type);
CREATE INDEX IF NOT EXISTS idx_mem_session ON agent_memory(session_id);
CREATE TRIGGER trg_memory_updated_at BEFORE UPDATE ON agent_memory
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_model_provider
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_model_provider (
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
CREATE INDEX IF NOT EXISTS idx_provider_tenant ON agent_model_provider(tenant_id);
CREATE INDEX IF NOT EXISTS idx_provider_name ON agent_model_provider(name);
CREATE TRIGGER trg_provider_updated_at BEFORE UPDATE ON agent_model_provider
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_api_key
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_api_key (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES agent_model_provider(id),
    alias       VARCHAR(128),
    key_value   TEXT NOT NULL DEFAULT '',
    expires_at  TIMESTAMP,
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag SMALLINT NOT NULL DEFAULT 0,
    tenant_id   BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(100),
    updated_by  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_apikey_provider ON agent_api_key(provider_id);
CREATE INDEX IF NOT EXISTS idx_apikey_tenant ON agent_api_key(tenant_id);
CREATE TRIGGER trg_apikey_updated_at BEFORE UPDATE ON agent_api_key
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_model_route
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_model_route (
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
CREATE INDEX IF NOT EXISTS idx_route_provider ON agent_model_route(provider_id);
CREATE INDEX IF NOT EXISTS idx_route_tenant ON agent_model_route(tenant_id);
CREATE INDEX IF NOT EXISTS idx_route_model ON agent_model_route(model_name);
CREATE TRIGGER trg_route_updated_at BEFORE UPDATE ON agent_model_route
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- capability_mcp
-- ============================================================
CREATE TABLE IF NOT EXISTS capability_mcp (
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
CREATE INDEX IF NOT EXISTS idx_mcp_tenant ON capability_mcp(tenant_id);
CREATE INDEX IF NOT EXISTS idx_mcp_name ON capability_mcp(name);
CREATE TRIGGER trg_mcp_updated_at BEFORE UPDATE ON capability_mcp
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- capability_skill
-- ============================================================
CREATE TABLE IF NOT EXISTS capability_skill (
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
CREATE INDEX IF NOT EXISTS idx_skill_tenant ON capability_skill(tenant_id);
CREATE INDEX IF NOT EXISTS idx_skill_name ON capability_skill(name);
CREATE TRIGGER trg_skill_updated_at BEFORE UPDATE ON capability_skill
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- capability_cli
-- ============================================================
CREATE TABLE IF NOT EXISTS capability_cli (
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
CREATE INDEX IF NOT EXISTS idx_cli_tenant ON capability_cli(tenant_id);
CREATE INDEX IF NOT EXISTS idx_cli_name ON capability_cli(name);
CREATE TRIGGER trg_cli_updated_at BEFORE UPDATE ON capability_cli
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_user
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(100) NOT NULL UNIQUE,
    password     VARCHAR(255) NOT NULL,
    display_name VARCHAR(200) NOT NULL DEFAULT '',
    email        VARCHAR(200),
    avatar       TEXT,
    english_name VARCHAR(64),
    super_admin  VARCHAR(10) NOT NULL DEFAULT 'NO',
    token        VARCHAR(500),
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag  SMALLINT NOT NULL DEFAULT 0,
    tenant_id    BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(100),
    updated_by   VARCHAR(100),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_tenant ON agent_user(tenant_id);
CREATE INDEX IF NOT EXISTS idx_user_username ON agent_user(username);
CREATE TRIGGER trg_user_updated_at BEFORE UPDATE ON agent_user
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- agent_tool_call_record
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_tool_call_record (
    id             BIGSERIAL PRIMARY KEY,
    step_id        BIGINT NOT NULL,
    run_id         BIGINT,
    session_id     BIGINT NOT NULL,
    tool_name      VARCHAR(200) NOT NULL,
    display_name   VARCHAR(200),
    arguments_json TEXT,
    artifact       TEXT,
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message  TEXT,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag    SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_toolcall_step ON agent_tool_call_record(step_id);
CREATE INDEX IF NOT EXISTS idx_toolcall_session ON agent_tool_call_record(session_id);

-- ============================================================
-- agent_compact_record
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_compact_record (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            BIGINT NOT NULL,
    status                VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    summary_before        TEXT,
    summary_after         TEXT,
    token_count           BIGINT,
    compacted_upto_run_id BIGINT DEFAULT 0,
    error_message         TEXT,
    tenant_id             BIGINT NOT NULL DEFAULT 0,
    created_by            VARCHAR(100),
    updated_by            VARCHAR(100),
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag           SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_compact_session ON agent_compact_record(session_id);

-- ============================================================
-- agent_user_in_loop_record
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_user_in_loop_record (
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
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag      SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_hitl_step ON agent_user_in_loop_record(step_id);
CREATE INDEX IF NOT EXISTS idx_hitl_session ON agent_user_in_loop_record(session_id);

-- ============================================================
-- agent_llm_interaction_record
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_llm_interaction_record (
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
CREATE INDEX IF NOT EXISTS idx_llm_interaction_run ON agent_llm_interaction_record (run_id);
CREATE INDEX IF NOT EXISTS idx_llm_interaction_session ON agent_llm_interaction_record (session_id);
CREATE INDEX IF NOT EXISTS idx_llm_interaction_created ON agent_llm_interaction_record (created_at);

-- ============================================================
-- seed data
-- ============================================================
INSERT INTO agent_user (username, password, display_name, super_admin, status)
VALUES ('admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', 'Administrator', 'YES',
        'ACTIVE')
ON CONFLICT (username) DO NOTHING;
