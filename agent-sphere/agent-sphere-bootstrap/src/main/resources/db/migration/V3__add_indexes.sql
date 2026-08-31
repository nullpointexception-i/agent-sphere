CREATE INDEX IF NOT EXISTS idx_toolcall_run ON agent_tool_call_record(run_id);
CREATE INDEX IF NOT EXISTS idx_llm_run_created ON agent_llm_interaction_record(run_id, delete_flag, created_at);
CREATE INDEX IF NOT EXISTS idx_toolcall_run_created ON agent_tool_call_record(run_id, session_id, delete_flag, created_at);
