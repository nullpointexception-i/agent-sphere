-- ============================================================
-- agent_document — LLM-generated documents per session
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_document (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(500) NOT NULL DEFAULT '',
    content       TEXT NOT NULL DEFAULT '',
    content_type  VARCHAR(20) NOT NULL DEFAULT 'markdown',
    session_id    BIGINT NOT NULL,
    instance_id   BIGINT NOT NULL,
    run_id        BIGINT,
    tenant_id     BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(100),
    updated_by    VARCHAR(100),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    delete_flag   SMALLINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_doc_session ON agent_document(session_id);
CREATE INDEX IF NOT EXISTS idx_doc_instance ON agent_document(instance_id);
CREATE INDEX IF NOT EXISTS idx_doc_tenant ON agent_document(tenant_id);
