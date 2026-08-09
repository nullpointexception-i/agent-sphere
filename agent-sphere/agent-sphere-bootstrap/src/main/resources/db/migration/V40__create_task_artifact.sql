-- V40: 任务契约 artifact（两阶段提炼结果落库）
-- 可扩展：artifact_type 区分不同类型（默认 task_contract）
-- content 存储校验通过的结构化 JSON；resultJson 取 artifact.content

CREATE TABLE IF NOT EXISTS agent_task_artifact (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT NOT NULL REFERENCES agent_task(id),
    artifact_type VARCHAR(50)  NOT NULL DEFAULT 'task_contract',
    content       TEXT,
    schema_ref    TEXT,
    run_id        BIGINT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    remark        VARCHAR(500),
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_task_artifact_task ON agent_task_artifact(task_id);
