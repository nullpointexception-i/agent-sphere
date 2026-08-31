-- ============================================================
-- agent_tool_call_record: add call_id, compressed fields
-- ============================================================
ALTER TABLE agent_tool_call_record
    ADD COLUMN IF NOT EXISTS call_id               VARCHAR(255) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS compressed_arguments  TEXT          DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS compressed_artifact   TEXT          DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_toolcall_call_id ON agent_tool_call_record(call_id);
