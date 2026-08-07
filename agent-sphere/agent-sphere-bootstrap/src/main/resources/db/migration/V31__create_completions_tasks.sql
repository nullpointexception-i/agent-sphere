-- V31: completions / tasks 能力开放层
-- 通用字段规范：status/delete_flag/tenant_id/created_by VARCHAR(100)/updated_by VARCHAR(100)/created_at/updated_at

-- completions 主表（可切换 prompt 版本）
CREATE TABLE IF NOT EXISTS agent_completions (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(200) NOT NULL DEFAULT '',
    description      TEXT,
    model_route_id   BIGINT,
    active_prompt_id BIGINT,
    input_schema     JSONB,
    output_schema    JSONB,
    config           JSONB,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag      SMALLINT NOT NULL DEFAULT 0,
    tenant_id        BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(100),
    updated_by       VARCHAR(100),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

-- prompt 版本表（独立维护版本，可切换）
CREATE TABLE IF NOT EXISTS agent_completions_prompt (
    id             BIGSERIAL PRIMARY KEY,
    completions_id BIGINT NOT NULL REFERENCES agent_completions(id),
    version        INT NOT NULL DEFAULT 1,
    prompt_system  TEXT,
    prompt_user    TEXT,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delete_flag    SMALLINT NOT NULL DEFAULT 0,
    tenant_id      BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(100),
    updated_by     VARCHAR(100),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 调用记录
CREATE TABLE IF NOT EXISTS agent_completions_call (
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
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- task 表（持久化任务，供调用方轮询）
CREATE TABLE IF NOT EXISTS agent_task (
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
    delete_flag          SMALLINT NOT NULL DEFAULT 0,
    tenant_id            BIGINT NOT NULL DEFAULT 0,
    created_by           VARCHAR(100),
    updated_by           VARCHAR(100),
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
