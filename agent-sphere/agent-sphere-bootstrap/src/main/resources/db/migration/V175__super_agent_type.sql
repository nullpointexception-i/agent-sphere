-- Super-Agent execution type: cloud instances (existing) or a local runtime.
ALTER TABLE agent_super_agent
    ADD COLUMN IF NOT EXISTS agent_type VARCHAR(16) NOT NULL DEFAULT 'CLOUD',
    ADD COLUMN IF NOT EXISTS local_runtime_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_super_agent_type
    ON agent_super_agent (tenant_id, agent_type, status) WHERE delete_flag = FALSE;
