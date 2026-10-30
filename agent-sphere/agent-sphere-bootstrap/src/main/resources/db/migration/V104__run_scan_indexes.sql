CREATE INDEX IF NOT EXISTS idx_run_status_updated_at
    ON agent_run(status, updated_at, id);

CREATE INDEX IF NOT EXISTS idx_compact_status_created_at
    ON agent_compact_record(status, created_at);
