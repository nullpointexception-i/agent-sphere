-- ============================================================
-- V144: retire the per-agent guardian Router session
-- ============================================================
-- Router sessions are now created per thread (agent_thread.router_session_id).
-- The JSON router mode and its guardian session are retired.

ALTER TABLE agent_super_agent
    DROP COLUMN IF EXISTS router_session_id;
