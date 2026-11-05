-- ============================================================
-- V143: per-thread Agent Router session
-- ============================================================
-- Each thread (DM / group) gets its own Router session so contexts do
-- not leak between conversations. Legacy threads fall back to the
-- guardian session until the column is populated.

ALTER TABLE agent_thread
    ADD COLUMN IF NOT EXISTS router_session_id BIGINT;
